package com.meepleradar.data.remote

import com.meepleradar.data.model.BggCollectionGame
import com.meepleradar.data.model.BggExpansion
import java.io.StringReader
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xml.sax.InputSource

interface BggCollectionApi {
    suspend fun ownedGames(username: String, apiToken: String): List<BggCollectionGame>
    suspend fun expansions(bggIds: List<Int>, apiToken: String): List<BggExpansion>
}

class BggXmlCollectionApi(
    private val transport: HttpTransport,
) : BggCollectionApi {

    private val expansionMutex = Mutex()
    private var lastExpansionRequestAt = 0L

    override suspend fun ownedGames(username: String, apiToken: String): List<BggCollectionGame> {
        val cleanUsername = username.trim()
        require(cleanUsername.isNotEmpty()) { "Inserisci il nome utente BGG." }
        val cleanToken = apiToken.trim()
        require(cleanToken.isNotEmpty()) { "Inserisci il token dell'applicazione BGG." }
        val encodedUsername = URLEncoder.encode(cleanUsername, Charsets.UTF_8.name())
        val url = "$BGG_API/collection?username=$encodedUsername&own=1&stats=1"

        try {
            repeat(MAX_ATTEMPTS) { attempt ->
                val response = transport.get(url, authHeaders(cleanToken)).trim()
                if (response.startsWith("<items", ignoreCase = true) ||
                    response.startsWith("<?xml", ignoreCase = true)
                ) {
                    return parseOwnedGames(response)
                }
                if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
            }
        } catch (e: java.io.IOException) {
            if (e.message?.contains("HTTP 401") == true) {
                throw IllegalStateException(
                    "BGG ha rifiutato il token (HTTP 401). Verifica che sia un token applicazione BGG valido.",
                    e,
                )
            }
            throw e
        }

        throw IllegalStateException(
            "BGG non ha ancora restituito la collezione. Riprova tra qualche secondo e verifica che sia pubblica.",
        )
    }

    override suspend fun expansions(bggIds: List<Int>, apiToken: String): List<BggExpansion> {
        require(bggIds.all { it > 0 }) { "ID BGG non valido." }
        val token = apiToken.trim()
        require(token.isNotEmpty()) { "Inserisci il token dell'applicazione BGG." }

        return expansionMutex.withLock {
            val result = mutableListOf<BggExpansion>()
            bggIds.distinct().chunked(THING_BATCH_SIZE).forEachIndexed { index, batch ->
                if (index > 0) {
                    val waitMs = (MIN_THING_INTERVAL_MS - (System.currentTimeMillis() - lastExpansionRequestAt))
                        .coerceAtLeast(0L)
                    if (waitMs > 0L) delay(waitMs)
                }
                val url = "$BGG_API/thing?id=${batch.joinToString(",")}&type=boardgame,boardgameexpansion"
                val xml = try {
                    (0 until MAX_ATTEMPTS).firstNotNullOfOrNull { attempt ->
                        val response = transport.get(url, authHeaders(token)).trim()
                        if (response.startsWith("<items", ignoreCase = true) ||
                            response.startsWith("<?xml", ignoreCase = true)
                        ) {
                            response
                        } else {
                            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
                            null
                        }
                    } ?: throw IllegalStateException(
                        "BGG non ha ancora restituito l'elenco delle espansioni. Riprova tra qualche secondo.",
                    )
                } catch (e: java.io.IOException) {
                    if (e.message?.contains("HTTP 401") == true) {
                        throw IllegalStateException(
                            "BGG ha rifiutato il token durante il caricamento delle espansioni (HTTP 401).",
                            e,
                        )
                    }
                    throw e
                }
                lastExpansionRequestAt = System.currentTimeMillis()
                result += parseExpansions(xml)
            }
            val orderMap = bggIds.distinct().withIndex().associate { it.value to it.index }
            result.distinctBy { it.expandsBggId to it.bggId }
                .sortedWith(compareBy({ orderMap[it.expandsBggId] ?: Int.MAX_VALUE }, { it.name.lowercase(java.util.Locale.ROOT) }))
        }
    }

    private fun parseExpansions(xml: String): List<BggExpansion> {
        require(!DOCTYPE_PATTERN.containsMatchIn(xml)) {
            "La risposta BGG contiene una dichiarazione DOCTYPE non consentita."
        }
        val document = secureFactory().newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(InputSource(StringReader(xml)))
        require(document.documentElement.tagName == "items") { "Risposta BGG non valida." }

        val items = document.getElementsByTagName("item")
        return (0 until items.length).flatMap { itemIndex ->
            val item = items.item(itemIndex) as? org.w3c.dom.Element ?: return@flatMap emptyList()
            val itemId = item.getAttribute("id").toIntOrNull() ?: return@flatMap emptyList()
            val itemType = item.getAttribute("type")
            val itemName = item.getElementsByTagName("name").item(0)?.let { node ->
                val element = node as? org.w3c.dom.Element
                element?.getAttribute("value")?.takeIf(String::isNotBlank) ?: node.textContent?.trim()
            }.orEmpty()
            val links = item.getElementsByTagName("link")
            val itemIsExpansion = itemType.equals("boardgameexpansion", ignoreCase = true)
            val relatedExpansions = (0 until links.length).mapNotNull { linkIndex ->
                val link = links.item(linkIndex) as? org.w3c.dom.Element ?: return@mapNotNull null
                val type = link.getAttribute("type")
                val id = link.getAttribute("id").toIntOrNull() ?: return@mapNotNull null
                val inbound = link.getAttribute("inbound").equals("true", ignoreCase = true)
                val linkName = link.getAttribute("value").trim()
                when {
                    itemIsExpansion && (
                        type == "boardgame" ||
                            type == "boardgameexpansion" && inbound ||
                            type == "expandsboardgame"
                        ) -> BggExpansion(
                            bggId = itemId,
                            name = itemName.ifBlank { "Espansione $itemId" },
                            expandsBggId = id,
                            url = "https://boardgamegeek.com/boardgameexpansion/$itemId",
                        )

                    !itemIsExpansion && type == "boardgameexpansion" && !inbound ->
                        BggExpansion(
                            bggId = id,
                            name = linkName.ifBlank { "Espansione $id" },
                            expandsBggId = itemId,
                            url = "https://boardgamegeek.com/boardgameexpansion/$id",
                        )

                    else -> null
                }
            }
            relatedExpansions
        }
    }

    private fun parseOwnedGames(xml: String): List<BggCollectionGame> {
        require(!DOCTYPE_PATTERN.containsMatchIn(xml)) {
            "La risposta BGG contiene una dichiarazione DOCTYPE non consentita."
        }
        val document = secureFactory().newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(InputSource(StringReader(xml)))
        require(document.documentElement.tagName == "items") { "Risposta BGG non valida." }

        val nodes = document.getElementsByTagName("item")
        return (0 until nodes.length).mapNotNull { index ->
            val item = nodes.item(index) as? org.w3c.dom.Element ?: return@mapNotNull null
            val bggId = item.getAttribute("objectid").toIntOrNull() ?: return@mapNotNull null
            val status = item.getElementsByTagName("status").item(0) as? org.w3c.dom.Element
            if (status?.getAttribute("own") != "1") return@mapNotNull null

            val name = item.getElementsByTagName("name").item(0)?.textContent?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val year = item.getElementsByTagName("yearpublished").item(0) as? org.w3c.dom.Element
            val rating = item.getElementsByTagName("average").item(0) as? org.w3c.dom.Element
            val image = item.getElementsByTagName("image").item(0)?.textContent?.trim()

            BggCollectionGame(
                bggId = bggId,
                name = name,
                kind = item.getAttribute("subtype").ifBlank { "boardgame" },
                yearPublished = year?.getAttribute("value")?.toIntOrNull(),
                imageUrl = image?.takeIf(String::isNotBlank),
                rating = rating?.getAttribute("value")?.toDoubleOrNull(),
            )
        }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }

    private fun authHeaders(token: String): Map<String, String> =
        HEADERS + ("Authorization" to "Bearer $token")

    private fun secureFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
        }

    private fun DocumentBuilderFactory.setFeatureIfSupported(feature: String, value: Boolean) {
        try {
            setFeature(feature, value)
        } catch (_: ParserConfigurationException) {
            // Android XML parsers may not implement these optional hardening features.
        }
    }

    private companion object {
        const val BGG_API = "https://boardgamegeek.com/xmlapi2"
        const val MAX_ATTEMPTS = 6
        const val RETRY_DELAY_MS = 2000L
        const val MIN_THING_INTERVAL_MS = 1000L
        const val THING_BATCH_SIZE = 20
        val HEADERS = mapOf("Accept" to "application/xml", "User-Agent" to "MeepleRadar/1.0")
        val DOCTYPE_PATTERN = Regex("<!DOCTYPE", RegexOption.IGNORE_CASE)
    }
}
