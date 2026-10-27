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

    suspend fun findDetailsById(bggId: Int): BoardGameDetails? = null

    suspend fun findCandidates(title: String, bggId: Int?): List<BoardGameDetails> = emptyList()

    suspend fun searchCandidates(query: String): List<BoardGameDetails> = emptyList()

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
    val englishName: String? = primaryName,
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
        return semaphore.withPermit {
            val knownDetails = bggId?.let { id ->
                detailsCache[id] ?: detailsFromThing(id)?.also { detailsCache[id] = it }
            }
            if (knownDetails != null &&
                (knownDetails.hasNoNames() || scoreMatch(title, knownDetails) >= MIN_NAME_MATCH_SCORE)
            ) {
                return@withPermit knownDetails
            }

            val ids = findIdsByName(title, bggId).take(MAX_NAME_MATCHES)
            var bestDetails: BoardGameDetails? = null
            var bestScore = -100000

            for (id in ids.distinct()) {
                val details = detailsCache[id] ?: detailsFromThing(id)
                if (details != null) detailsCache[id] = details
                if (details != null) {
                    val score = scoreMatch(title, details)
                    if (score >= MIN_NAME_MATCH_SCORE && score > bestScore) {
                        bestScore = score
                        bestDetails = details
                    }
                    if (details.expansions.isNotEmpty() && score >= MIN_EXPANSION_PARENT_SCORE) {
                        for (expansion in details.expansions) {
                            val expansionDetails = detailsCache[expansion.bggId]
                                ?: detailsFromThing(expansion.bggId)
                            if (expansionDetails != null) {
                                detailsCache[expansion.bggId] = expansionDetails
                                val expansionScore = scoreMatch(title, expansionDetails)
                                if (expansionScore >= MIN_NAME_MATCH_SCORE && expansionScore > bestScore) {
                                    bestScore = expansionScore
                                    bestDetails = expansionDetails
                                }
                            }
                        }
                    }
                }
            }
            bestDetails
        }
    }

    override suspend fun findDetailsById(bggId: Int): BoardGameDetails? =
        detailsCache[bggId] ?: semaphore.withPermit {
            detailsCache[bggId] ?: detailsFromThing(bggId)?.also { detailsCache[bggId] = it }
        }

    override suspend fun findCandidates(title: String, bggId: Int?): List<BoardGameDetails> =
        semaphore.withPermit {
            val baseTitle = title.replace(Regex("\\(.*?\\)"), "")
                .split(Regex("\\s+[-–—]\\s+"), limit = 2)
                .first()
                .trim()
            val ids = mutableListOf<Int>()
            bggId?.let(ids::add)
            ids += findIdsByName(title, bggId)
            if (normalize(baseTitle) != normalize(title)) {
                ids += findIdsByName(baseTitle, bggId)
            }

            val candidates = linkedMapOf<Int, BoardGameDetails>()
            for (id in ids.distinct().take(MAX_CANDIDATE_SEARCH_IDS)) {
                val details = detailsCache[id] ?: detailsFromThing(id)?.also { detailsCache[id] = it }
                if (details != null) {
                    if (id == bggId && scoreMatch(baseTitle, details) < MIN_EXPANSION_PARENT_SCORE) {
                        continue
                    }
                    candidates[id] = details
                    if (scoreMatch(baseTitle, details) >= MIN_EXPANSION_PARENT_SCORE) {
                        for (expansion in details.expansions) {
                            val expansionDetails = detailsCache[expansion.bggId]
                                ?: detailsFromThing(expansion.bggId)?.also { detailsCache[expansion.bggId] = it }
                            if (expansionDetails != null) candidates[expansion.bggId] = expansionDetails
                        }
                    }
                }
            }

            candidates.values
                .map { details -> details to scoreMatch(title, details) }
                .filter { (_, score) -> score > 0 }
                .sortedByDescending { (_, score) -> score }
                .take(MAX_MANUAL_CANDIDATES)
                .map { (details, _) -> details }
        }

    override suspend fun searchCandidates(query: String): List<BoardGameDetails> =
        semaphore.withPermit {
            if (query.isBlank()) return@withPermit emptyList()
            val ids = (
                searchBggIds(query, query, null) +
                    searchBggHtmlIds(query, null)
                ).distinct().take(MAX_MANUAL_SEARCH_RESULTS)
            ids.mapNotNull { id ->
                detailsCache[id] ?: detailsFromThing(id)?.also { detailsCache[id] = it }
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
        val matchTitle = targetTitle.replace(Regex("\\(.*?\\)"), "").trim()
        val normTarget = normalize(matchTitle)
        if (normTarget.isEmpty()) return 0
        val targetWords = significantWords(matchTitle).toSet()
        val baseWords = details.expands
            .flatMap { significantWords(it.name) }
            .toSet()
        val expansionBonus = if (details.expands.isNotEmpty() && targetWords.size > baseWords.size) 1000 else 0

        val candidateNames = mutableListOf<String>()
        details.primaryName?.let { candidateNames.add(it) }
        candidateNames.addAll(details.alternateNames)

        var maxScore = -10000

        for (candName in candidateNames) {
            val normCand = normalize(candName)
            if (normCand.isEmpty()) continue

            val candWords = significantWords(candName).map(::canonicalWord).toSet()
            val matchedWords = targetWords.map(::canonicalWord).count { it in candWords }
            val score = when {
                normCand == normTarget -> 10000
                normCand.contains(normTarget) -> 9000 + normTarget.length
                targetWords.isNotEmpty() -> matchedWords * 10000 / targetWords.size
                else -> 0
            }
            maxScore = maxOf(maxScore, score + expansionBonus)
        }

        if (maxScore < 0 && !details.description.isNullOrBlank()) {
            val normDesc = normalize(details.description)
            val targetWords = targetTitle.lowercase(Locale.ROOT)
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 4 && normalize(it) !in COMMON_WORDS }
                .map { normalize(it) }

            var descScore = 0
            for (word in targetWords) {
                if (normDesc.contains(word)) {
                    descScore += 100
                }
            }
            maxScore = maxOf(maxScore, descScore)
        }

        return maxScore
    }

    private fun significantWords(value: String): List<String> =
        value.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && normalize(it) !in COMMON_WORDS }
            .map { normalize(it) }

    private fun canonicalWord(word: String): String = when (word) {
        "guerra" -> "war"
        "deserto" -> "desert"
        "gilda" -> "guild"
        "spaziale" -> "space"
        "spacing" -> "space"
        else -> word
    }

    private fun BoardGameDetails.hasNoNames(): Boolean =
        primaryName.isNullOrBlank() && alternateNames.isEmpty()

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
        val titleParts = query.split(Regex("\\s+[-–—]\\s+"), limit = 2)
        val baseTitle = titleParts.first()
        val subtitle = titleParts.getOrNull(1)?.trim()
        val relatedBaseIds = if (baseTitle != query) {
            (searchBggIds(baseTitle, title, excludedId) + searchBggHtmlIds(baseTitle, excludedId))
        } else {
            emptyList()
        }
        val apiIds = (listOf(query, subtitle).filterNotNull().distinct())
            .flatMap { searchBggIds(it, title, excludedId) }
        val htmlIds = searchBggHtmlIds(query, excludedId)
        return (relatedBaseIds + apiIds + htmlIds).distinct()
    }

    private suspend fun searchBggHtmlIds(query: String, excludedId: Int?): List<Int> {
        val cleanQuery = query.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim().replace(Regex("\\s+"), " ")
        if (cleanQuery.isBlank()) return emptyList()

        val searchUrl = "https://boardgamegeek.com/search/boardgame?q=${java.net.URLEncoder.encode(cleanQuery, "UTF-8")}"
        val htmlText = runCatching { transport.get(searchUrl, XML_HEADERS) }.getOrNull() ?: return emptyList()

        val regex = Regex("""/boardgame(?:expansion)?/(\d+)/""")
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
                val exactScore = when {
                    normName == normOriginal -> 10000
                    normName == normQuery -> 9000
                    normOriginal.isNotEmpty() && normName.isNotEmpty() && normOriginal.contains(normName) -> 500 + normName.length
                    normOriginal.isNotEmpty() && normName.isNotEmpty() && normName.contains(normOriginal) -> 400 + normOriginal.length
                    normQuery.length >= 4 && normName.contains(normQuery) -> 300 + normName.length
                    normQuery.length >= 4 && normQuery.contains(normName) -> 200 + normName.length
                    else -> 0
                }
                    val queryWords = significantWords(originalTitle).map(::canonicalWord).toSet()
                    val nameWords = significantWords(name).map(::canonicalWord).toSet()
                    val tokenScore = if (queryWords.isEmpty()) 0 else
                        queryWords.intersect(nameWords).size * 1000 / queryWords.size
                val score = maxOf(exactScore, tokenScore)
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
        val alternateNames = (
            parseLinks("boardgamename") +
                listOfNotNull(root["alternatenames"], item["alternatenames"])
                    .flatMap { names ->
                        runCatching {
                            names.jsonArray
                                .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                        }.getOrNull().orEmpty()
                    }
            ).distinct()

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
        const val MAX_CANDIDATE_SEARCH_IDS = 30
        const val MAX_MANUAL_CANDIDATES = 30
        const val MAX_MANUAL_SEARCH_RESULTS = 50
        const val MIN_NAME_MATCH_SCORE = 8000
        const val MIN_EXPANSION_PARENT_SCORE = 6000
        val GAME_TYPES = setOf("boardgame", "boardgameexpansion")
        val XML_HEADERS = mapOf("Accept" to "application/xml", "User-Agent" to "MeepleRadar/1.0")
        val JSON_HEADERS = mapOf("Accept" to "application/json", "User-Agent" to "MeepleRadar/1.0")
        val COMMON_WORDS = setOf(
            "game", "edition", "set", "gioco", "rosso", "verde", "blu", "box", "boardgame",
            "expansion", "oscuro", "signore", "la", "del", "della", "delle", "per", "for", "the",
        )
    }
}
