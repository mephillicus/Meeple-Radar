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

data class BggLink(
    val bggId: Int,
    val name: String,
    val url: String?,
)

data class BggSeries(
    val bases: List<BggLink>,
    val expansions: List<BggLink>,
    val siblings: List<BggLink>,
)

interface BoardGameImageLookup {
    suspend fun findImage(title: String, bggId: Int?): String?

    suspend fun findDetails(title: String, bggId: Int?): BoardGameDetails? = null

    suspend fun findSeries(bggId: Int): BggSeries? = null
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
    val expansions: List<BggLink> = emptyList(),
    val expands: List<BggLink> = emptyList(),
    val primaryName: String? = null,
    val alternateNames: List<String> = emptyList(),
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
            var bestDetails: BoardGameDetails? = null
            var bestScore = -100000

            for (id in ids.distinct()) {
                val details = detailsCache[id] ?: detailsFromThing(id)
                detailsCache[id] = details
                if (details != null) {
                    val score = scoreMatch(title, details)
                    if (score > bestScore) {
                        bestScore = score
                        bestDetails = details
                    }
                }
            }
            bestDetails
        }
    }

    override suspend fun findSeries(bggId: Int): BggSeries? = runCatching {
        if (bggId <= 0) return null
        val details = detailsCache[bggId] ?: semaphore.withPermit {
            detailsCache[bggId] ?: detailsFromThing(bggId).also { detailsCache[bggId] = it }
        } ?: return null

        if (details.expands.isNotEmpty()) {
            val bases = details.expands
            val siblings = mutableListOf<BggLink>()
            for (base in bases.take(2)) {
                val baseDetails = detailsCache[base.bggId] ?: semaphore.withPermit {
                    detailsCache[base.bggId] ?: detailsFromThing(base.bggId).also { detailsCache[base.bggId] = it }
                }
                baseDetails?.expansions?.forEach { exp ->
                    if (exp.bggId != bggId) {
                        siblings.add(exp)
                    }
                }
            }
            val dedupSiblings = siblings.distinctBy { it.bggId }
            BggSeries(bases = bases, expansions = emptyList(), siblings = dedupSiblings)
        } else if (details.expansions.isNotEmpty()) {
            BggSeries(bases = emptyList(), expansions = details.expansions, siblings = emptyList())
        } else {
            null
        }
    }.getOrNull()

    private fun scoreMatch(targetTitle: String, details: BoardGameDetails): Int {
        val normTarget = normalize(targetTitle)
        if (normTarget.isEmpty()) return 0

        val candidateNames = mutableListOf<String>()
        details.primaryName?.let { candidateNames.add(it) }
        candidateNames.addAll(details.alternateNames)

        var maxScore = -10000

        for (candName in candidateNames) {
            val normCand = normalize(candName)
            if (normCand.isEmpty()) continue

            var score = when {
                normCand == normTarget -> 10000
                normTarget.contains(normCand) -> 5000 + normCand.length
                normCand.contains(normTarget) -> 4000 + normTarget.length
                else -> 0
            }

            val targetWords = targetTitle.lowercase(Locale.ROOT)
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 3 && normalize(it) !in COMMON_WORDS }
                .map { normalize(it) }
                .toSet()

            val candWords = candName.lowercase(Locale.ROOT)
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 3 && normalize(it) !in COMMON_WORDS }
                .map { normalize(it) }
                .toSet()

            if (targetWords.isNotEmpty() && candWords.isNotEmpty()) {
                val common = targetWords.intersect(candWords)
                val overlapRatio = common.size.toDouble() / targetWords.size
                val wordScore = (overlapRatio * 3000).toInt()
                score = maxOf(score, wordScore)

                val missingWords = targetWords - candWords
                if (missingWords.isNotEmpty()) {
                    score -= missingWords.size * 500
                }
            }

            maxScore = maxOf(maxScore, score)
        }

        if (maxScore < 1000 && !details.description.isNullOrBlank()) {
            val normDesc = normalize(details.description)
            val targetWords = targetTitle.lowercase(Locale.ROOT)
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 4 && normalize(it) !in COMMON_WORDS }
                .map { normalize(it) }

            var descScore = 100
            for (word in targetWords) {
                if (normDesc.contains(word)) {
                    descScore += 200
                }
            }
            maxScore = maxOf(maxScore, descScore)
        }

        return maxScore
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

        val clean = title.replace(Regex("\\(.*?\\)"), "").trim()
        val query = if (clean.isBlank()) title else clean

        var htmlIds = searchBggHtmlIds(query, excludedId)
        if (htmlIds.isNotEmpty()) return htmlIds

        val withoutHyphen = query.replace(Regex("[-–—].*"), "").trim()
        if (withoutHyphen != query && withoutHyphen.isNotBlank()) {
            htmlIds = searchBggHtmlIds(withoutHyphen, excludedId)
            if (htmlIds.isNotEmpty()) return htmlIds
        }

        val words = query.replace(Regex("[-–—].*"), "").replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").split(Regex("\\s+")).filter { it.length > 2 }
        if (words.isNotEmpty()) {
            val shortQuery = words.take(3).joinToString(" ")
            val ids = searchBggIds(shortQuery, title, excludedId)
            if (ids.isNotEmpty()) return ids
        }

        val ids = searchBggIds(query, title, excludedId)
        if (ids.isNotEmpty()) return ids

        return emptyList()
    }

    private suspend fun searchBggHtmlIds(query: String, excludedId: Int?): List<Int> {
        val cleanQuery = query.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim().replace(Regex("\\s+"), " ")
        if (cleanQuery.isBlank()) return emptyList()

        val searchUrl = "https://boardgamegeek.com/search/boardgame?q=${java.net.URLEncoder.encode(cleanQuery, "UTF-8")}"
        val htmlText = runCatching { transport.get(searchUrl, XML_HEADERS) }.getOrNull() ?: return emptyList()

        val regex = Regex("""/boardgame/(\d+)/""")
        return regex.findAll(htmlText)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filterNot { it == excludedId }
            .distinct()
            .toList()
    }

    private suspend fun searchBggIds(query: String, originalTitle: String, excludedId: Int?): List<Int> {
        val cleanQuery = query.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim().replace(Regex("\\s+"), " ")
        if (cleanQuery.isBlank()) return emptyList()

        val searchUrl = endpoint("search") {
            addQueryParameter("query", cleanQuery)
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
                val score = when {
                    normName == normOriginal -> 1000
                    normName == normQuery -> 900
                    normOriginal.isNotEmpty() && normName.isNotEmpty() && normOriginal.contains(normName) -> 500 + normName.length
                    normOriginal.isNotEmpty() && normName.isNotEmpty() && normName.contains(normOriginal) -> 400 + normOriginal.length
                    normQuery.length >= 4 && normName.contains(normQuery) -> 300 + normName.length
                    normQuery.length >= 4 && normQuery.contains(normName) -> 200 + normName.length
                    else -> 0
                }
                bestScore = maxOf(bestScore, score)
            }

            if (bestScore > 0) {
                candidates.add(id to bestScore)
            }
        }

        val matched = candidates.sortedByDescending { it.second }.map { it.first }.distinct()
        if (matched.isNotEmpty()) return matched

        return items.mapNotNull { it.attributes?.getNamedItem("id")?.nodeValue?.toIntOrNull() }
            .filterNot { it == excludedId }
            .distinct()
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

        if (bggDetails != null && (!bggDetails.description.isNullOrBlank() || !bggDetails.imageUrl.isNullOrBlank() || bggDetails.expansions.isNotEmpty() || bggDetails.expands.isNotEmpty())) {
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

        val primaryName = item["name"]?.jsonPrimitive?.contentOrNull

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

        fun parseBggLinks(key: String): List<BggLink> = runCatching {
            links?.get(key)?.jsonArray?.mapNotNull { el ->
                val obj = el.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val objectId = obj["objectid"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val canonical = obj["canonical_link"]?.jsonPrimitive?.contentOrNull
                val url = when {
                    canonical.isNullOrBlank() -> "https://boardgamegeek.com/boardgame/$objectId"
                    canonical.startsWith("http://") || canonical.startsWith("https://") -> canonical
                    canonical.startsWith("/") -> "https://boardgamegeek.com$canonical"
                    else -> "https://boardgamegeek.com/$canonical"
                }
                BggLink(bggId = objectId, name = name, url = url)
            }?.distinctBy { it.bggId }
        }.getOrNull() ?: emptyList()

        val designers = parseLinks("boardgamedesigner")
        val categories = parseLinks("boardgamecategory")
        val mechanics = parseLinks("boardgamemechanic")
        val alternateNames = parseLinks("boardgamename")

        val expansions = parseBggLinks("boardgameexpansion")
        val expands = parseBggLinks("expandsboardgame")

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
            expansions = expansions,
            expands = expands,
            primaryName = primaryName,
            alternateNames = alternateNames,
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

        fun bggLinks(type: String): List<BggLink> =
            (0 until children.length).mapNotNull { children.item(it) }
                .filter { it.nodeName == "link" && it.attributes?.getNamedItem("type")?.nodeValue == type }
                .mapNotNull { node ->
                    val bggId = node.attributes?.getNamedItem("id")?.nodeValue?.toIntOrNull() ?: return@mapNotNull null
                    val name = node.attributes?.getNamedItem("value")?.nodeValue ?: return@mapNotNull null
                    BggLink(bggId = bggId, name = name, url = "https://boardgamegeek.com/boardgame/$bggId")
                }
                .distinctBy { it.bggId }

        val image = (0 until children.length).mapNotNull { children.item(it) }
            .firstOrNull { it.nodeName == "image" }
            ?.textContent
            ?.trim()
            ?.takeIf { it.startsWith("https://") }

        val primaryName = (0 until children.length).mapNotNull { children.item(it) }
            .firstOrNull { it.nodeName == "name" && it.attributes?.getNamedItem("type")?.nodeValue == "primary" }
            ?.attributes?.getNamedItem("value")?.nodeValue

        val alternateNames = (0 until children.length).mapNotNull { children.item(it) }
            .filter { it.nodeName == "name" && it.attributes?.getNamedItem("type")?.nodeValue == "alternate" }
            .mapNotNull { it.attributes?.getNamedItem("value")?.nodeValue }

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
            expansions = bggLinks("boardgameexpansion"),
            expands = bggLinks("boardgameexpansion"),
            primaryName = primaryName,
            alternateNames = alternateNames,
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
        val COMMON_WORDS = setOf("game", "edition", "set", "gioco", "rosso", "verde", "blu", "box", "boardgame", "expansion", "oscuro", "signore")
    }
}
