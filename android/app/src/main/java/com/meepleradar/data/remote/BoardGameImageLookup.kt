package com.meepleradar.data.remote

import java.io.StringReader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.xml.sax.InputSource

interface BoardGameImageLookup {
    suspend fun findImage(title: String, bggId: Int?): String?

    suspend fun findDetails(title: String, bggId: Int?): BoardGameDetails? = null
}

data class BoardGameDetails(
    val bggId: Int,
    val imageUrl: String?,
    val description: String?,
    val yearPublished: Int?,
    val minPlayers: Int?,
    val maxPlayers: Int?,
    val playingTime: Int?,
    val minAge: Int?,
    val designers: List<String>,
    val categories: List<String>,
    val mechanics: List<String>,
)

class BoardGameGeekImageLookup(
    private val transport: HttpTransport,
    private val geekGroupApi: GeekGroupApi? = null,
) : BoardGameImageLookup {

    private val cache = ConcurrentHashMap<String, String>()
    private val detailsCache = ConcurrentHashMap<Int, BoardGameDetails?>()
    private val semaphore = Semaphore(4)

    override suspend fun findImage(title: String, bggId: Int?): String? {
        val cacheKey = bggId?.let { "id:$it" } ?: "name:${normalize(title)}"
        cache[cacheKey]?.let { return it.ifEmpty { null } }

        return semaphore.withPermit {
            cache[cacheKey]?.let { return@withPermit it.ifEmpty { null } }

            val image = findById(bggId) ?: findByName(title, bggId)
            cache[cacheKey] = image.orEmpty()
            image
        }
    }

    override suspend fun findDetails(title: String, bggId: Int?): BoardGameDetails? {
        if (bggId != null) {
            detailsCache[bggId]?.let { return it }
        }

        return semaphore.withPermit {
            if (bggId != null) {
                detailsCache[bggId]?.let { return@withPermit it }
                val details = detailsFromThing(bggId)
                detailsCache[bggId] = details
                if (details != null) return@withPermit details
            }

            val ids = findIdsByName(title, bggId).take(MAX_NAME_MATCHES)
            for (id in ids.distinct()) {
                detailsCache[id]?.let { return@withPermit it }
                val details = detailsFromThing(id)
                detailsCache[id] = details
                if (details != null) return@withPermit details
            }
            null
        }
    }

    private suspend fun findById(id: Int?): String? =
        id?.let { detailsFromThing(it)?.imageUrl }

    private suspend fun findByName(title: String, excludedId: Int?): String? {
        for (id in findIdsByName(title, excludedId).take(MAX_NAME_MATCHES)) {
            detailsFromThing(id)?.imageUrl?.let { return it }
        }
        return null
    }

    private suspend fun findIdsByName(title: String, excludedId: Int?): List<Int> {
        if (title.isBlank()) return emptyList()
        val searchUrl = endpoint("search") {
            addQueryParameter("query", title)
            addQueryParameter("type", "boardgame,boardgameexpansion")
        }
        val document = parseXml(transport.get(searchUrl, XML_HEADERS))
        val expectedName = normalize(title)
        return document.getElementsByTagName("item")
            .let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) } }
            .filter { it.attributes?.getNamedItem("type")?.nodeValue in GAME_TYPES }
            .filter { item ->
                val names = item.childNodes
                (0 until names.length).mapNotNull { names.item(it) }
                    .firstOrNull {
                        it.nodeName == "name" &&
                            it.attributes?.getNamedItem("type")?.nodeValue == "primary"
                    }
                    ?.attributes
                    ?.getNamedItem("value")
                    ?.nodeValue
                    ?.let(::normalize) == expectedName
            }
            .mapNotNull { it.attributes?.getNamedItem("id")?.nodeValue?.toIntOrNull() }
            .filterNot { it == excludedId }
            .distinct()
    }

    private suspend fun detailsFromThing(id: Int): BoardGameDetails? {
        val url = endpoint("thing") {
            addQueryParameter("id", id.toString())
            addQueryParameter("type", "boardgame,boardgameexpansion")
        }
        val document = parseXml(transport.get(url, XML_HEADERS))
        val item = document.getElementsByTagName("item").item(0) ?: return null
        val children = item.childNodes
        fun value(name: String): String? =
            (0 until children.length).mapNotNull { children.item(it) }
                .firstOrNull { it.nodeName == name }
                ?.attributes
                ?.getNamedItem("value")
                ?.nodeValue

        fun integer(name: String): Int? = value(name)?.toIntOrNull()
        fun text(name: String): String? =
            (0 until children.length).mapNotNull { children.item(it) }
                .firstOrNull { it.nodeName == name }
                ?.textContent
                ?.trim()
        fun names(type: String): List<String> =
            (0 until children.length).mapNotNull { children.item(it) }
                .filter { it.nodeName == "link" && it.attributes?.getNamedItem("type")?.nodeValue == type }
                .mapNotNull { it.attributes?.getNamedItem("value")?.nodeValue }
                .distinct()

        val image = (0 until children.length).mapNotNull { children.item(it) }
            .firstOrNull { it.nodeName == "image" }
            ?.textContent
            ?.trim()
            ?.takeIf { it.startsWith("https://") }

        return BoardGameDetails(
            bggId = id,
            imageUrl = image,
            description = text("description")?.takeIf(String::isNotBlank),
            yearPublished = integer("yearpublished"),
            minPlayers = integer("minplayers"),
            maxPlayers = integer("maxplayers"),
            playingTime = integer("playingtime"),
            minAge = integer("minage"),
            designers = names("boardgamedesigner"),
            categories = names("boardgamecategory"),
            mechanics = names("boardgamemechanic"),
        )
    }

    private fun endpoint(path: String, configure: okhttp3.HttpUrl.Builder.() -> Unit): String =
        "$BGG_API/$path".toHttpUrl().newBuilder().apply(configure).build().toString()

    private fun parseXml(xml: String) = DocumentBuilderFactory.newInstance().run {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private companion object {
        const val BGG_API = "https://boardgamegeek.com/xmlapi2"
        const val MAX_NAME_MATCHES = 3
        val GAME_TYPES = setOf("boardgame", "boardgameexpansion")
        val XML_HEADERS = mapOf("Accept" to "application/xml")
    }
}
