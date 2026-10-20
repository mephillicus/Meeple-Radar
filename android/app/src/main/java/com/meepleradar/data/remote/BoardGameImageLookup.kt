package com.meepleradar.data.remote

import java.io.StringReader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val clean = title.replace(Regex("\\(.*?\\)"), "").replace(Regex("[-–—].*"), "").trim()
        val query = if (clean.isBlank()) title else clean
        if (query.isBlank()) return emptyList()

        var ids = searchBggIds(query, title, excludedId)
        if (ids.isNotEmpty()) return ids

        val words = query.split(Regex("\\s+")).filter { it.length > 2 }
        if (words.size > 2) {
            val shortQuery = words.take(3).joinToString(" ")
            ids = searchBggIds(shortQuery, title, excludedId)
            if (ids.isNotEmpty()) return ids
        }

        return emptyList()
    }

    private suspend fun searchBggIds(query: String, originalTitle: String, excludedId: Int?): List<Int> {
        val searchUrl = endpoint("search") {
            addQueryParameter("query", query)
            addQueryParameter("type", "boardgame,boardgameexpansion")
        }
        val xmlText = runCatching { transport.get(searchUrl, XML_HEADERS) }.getOrNull() ?: return emptyList()
        val document = runCatching { parseXml(xmlText) }.getOrNull() ?: return emptyList()

        val normOriginal = normalize(originalTitle)
        val normQuery = normalize(query)
        val items = document.getElementsByTagName("item")
            .let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) } }
            .filter { it.attributes?.getNamedItem("type")?.nodeValue in GAME_TYPES }

        val candidates = mutableListOf<Pair<Int, Int>>()

        for (item in items) {
            val id = item.attributes?.getNamedItem("id")?.nodeValue?.toIntOrNull() ?: continue
            if (id == excludedId) continue

            val names = item.childNodes
            val nameList = (0 until names.length).mapNotNull { names.item(it) }
                .filter { it.nodeName == "name" }
                .mapNotNull { it.attributes?.getNamedItem("value")?.nodeValue }

            var bestScore = 0
            for (name in nameList) {
                val normName = normalize(name)
                when {
                    normName == normOriginal -> bestScore = maxOf(bestScore, 100)
                    normName == normQuery -> bestScore = maxOf(bestScore, 90)
                    normOriginal.isNotEmpty() && normName.isNotEmpty() && (normOriginal.contains(normName) || normName.contains(normOriginal)) -> bestScore = maxOf(bestScore, 80)
                    normQuery.length >= 4 && normName.contains(normQuery) -> bestScore = maxOf(bestScore, 70)
                    normQuery.length >= 4 && normQuery.contains(normName) -> bestScore = maxOf(bestScore, 60)
                }
            }

            if (bestScore > 0) {
                candidates.add(id to bestScore)
            }
        }

        return candidates.sortedByDescending { it.second }.map { it.first }.distinct()
    }

    private suspend fun detailsFromThing(id: Int): BoardGameDetails? {
        val jsonUrl = "https://api.geekdo.com/api/geekitems?objectid=$id&objecttype=thing"
        val bggDetails = runCatching {
            val responseText = transport.get(jsonUrl, JSON_HEADERS)
            if (responseText.isNotBlank()) {
                parseGeekDoJson(id, responseText)
            } else {
                null
            }
        }.getOrNull()

        if (bggDetails != null && (!bggDetails.description.isNullOrBlank() || !bggDetails.imageUrl.isNullOrBlank())) {
            return bggDetails
        }

        if (geekGroupApi != null) {
            val ggDetails = runCatching { geekGroupApi?.getGameDetails(id) }.getOrNull()
            if (ggDetails != null) {
                return ggDetails
            }
        }

        return runCatching { detailsFromXmlThing(id) }.getOrNull()
    }

    private fun parseGeekDoJson(id: Int, jsonText: String): BoardGameDetails? {
        val root = runCatching { Json.parseToJsonElement(jsonText.trim()).jsonObject }.getOrNull() ?: return null
        val item = root["item"]?.jsonObject ?: return null

        val rawImage = item["imageurl"]?.jsonPrimitive?.contentOrNull
        val imageUrl = rawImage?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { if (it.startsWith("http://")) "https://${it.removePrefix("http://")}" else it }

        val description = item["description"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }

        val yearPublished = item["yearpublished"]?.jsonPrimitive?.intOrNull
        val minPlayers = item["minplayers"]?.jsonPrimitive?.intOrNull
        val maxPlayers = item["maxplayers"]?.jsonPrimitive?.intOrNull
        val playingTime = item["playingtime"]?.jsonPrimitive?.intOrNull
            ?: item["maxplaytime"]?.jsonPrimitive?.intOrNull
            ?: item["minplaytime"]?.jsonPrimitive?.intOrNull
        val minAge = item["minage"]?.jsonPrimitive?.intOrNull

        val links = item["links"]?.jsonObject
        fun parseLinks(key: String): List<String> = runCatching {
            links?.get(key)?.jsonArray
                ?.mapNotNull { el ->
                    when {
                        el.jsonObject.containsKey("name") -> el.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                        else -> el.jsonPrimitive.contentOrNull
                    }
                }
                ?.distinct()
        }.getOrNull() ?: emptyList()

        val designers = parseLinks("boardgamedesigner")
        val categories = parseLinks("boardgamecategory")
        val mechanics = parseLinks("boardgamemechanic")

        return BoardGameDetails(
            bggId = id,
            imageUrl = imageUrl,
            description = description,
            yearPublished = yearPublished,
            minPlayers = minPlayers,
            maxPlayers = maxPlayers,
            playingTime = playingTime,
            minAge = minAge,
            designers = designers,
            categories = categories,
            mechanics = mechanics,
        )
    }

    private suspend fun detailsFromXmlThing(id: Int): BoardGameDetails? {
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

    private fun normalize(value: String): String {
        val unaccented = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return unaccented.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    }

    private companion object {
        const val BGG_API = "https://boardgamegeek.com/xmlapi2"
        const val MAX_NAME_MATCHES = 3
        val GAME_TYPES = setOf("boardgame", "boardgameexpansion")
        val XML_HEADERS = mapOf("Accept" to "application/xml", "User-Agent" to "MeepleRadar/1.0")
        val JSON_HEADERS = mapOf("Accept" to "application/json", "User-Agent" to "MeepleRadar/1.0")
    }
}
