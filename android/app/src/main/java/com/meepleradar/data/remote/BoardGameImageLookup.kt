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

    suspend fun refreshDetailsById(bggId: Int): BoardGameDetails? = findDetailsById(bggId)

    suspend fun findCandidates(title: String, bggId: Int?): List<BoardGameDetails> = emptyList()

    suspend fun searchCandidates(title: String, bggId: Int? = null): List<BoardGameDetails> = findCandidates(title, bggId)

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
    val bggRating: Double? = null,
    val bggRank: Int? = null,
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
        val candidates = findCandidates(title, bggId)
        return candidates.firstOrNull()
    }

    override suspend fun findDetailsById(bggId: Int): BoardGameDetails? {
        if (bggId <= 0) return null
        detailsCache[bggId]?.let { return it }
        return semaphore.withPermit {
            detailsCache[bggId] ?: detailsFromThing(bggId).also { detailsCache[bggId] = it }
        }
    }

    override suspend fun refreshDetailsById(bggId: Int): BoardGameDetails? {
        if (bggId <= 0) return null
        return semaphore.withPermit {
            detailsFromThing(bggId)?.also { detailsCache[bggId] = it }
        }
    }

    override suspend fun findCandidates(title: String, bggId: Int?): List<BoardGameDetails> {
        val candidateIds = mutableListOf<Int>()
        val foundIds = findIdsByName(title, null)
        for (id in foundIds) {
            if (id !in candidateIds) {
                candidateIds.add(id)
            }
        }

        if (bggId != null && bggId > 0) {
            val bggIdDetails = detailsCache[bggId] ?: semaphore.withPermit {
                detailsCache[bggId] ?: detailsFromThing(bggId).also { detailsCache[bggId] = it }
            }
            if (bggIdDetails != null) {
                val validationScore = scoreMatch(title, bggIdDetails)
                if (validationScore >= 8000) {
                    if (bggId !in candidateIds) candidateIds.add(0, bggId)
                }
                bggIdDetails.expansions.forEach { exp ->
                    if (exp.bggId !in candidateIds) {
                        candidateIds.add(exp.bggId)
                    }
                }
            }
        }

        val baseCandidateIds = candidateIds.toList()
        for (id in baseCandidateIds) {
            val d = detailsCache[id] ?: semaphore.withPermit {
                detailsCache[id] ?: detailsFromThing(id).also { detailsCache[id] = it }
            }
            d?.expansions?.forEach { exp ->
                if (exp.bggId !in candidateIds) {
                    candidateIds.add(exp.bggId)
                }
            }
        }

        val evaluated = mutableListOf<Pair<BoardGameDetails, Int>>()
        for (id in candidateIds.take(MAX_CANDIDATE_SEARCH_IDS)) {
            val details = detailsCache[id] ?: semaphore.withPermit {
                detailsCache[id] ?: detailsFromThing(id).also { detailsCache[id] = it }
            }
            if (details != null) {
                val score = scoreMatch(title, details)
                if (score > -10000) {
                    evaluated.add(details to score)
                }
            }
        }

        return evaluated.sortedByDescending { it.second }.map { it.first }
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

        val targetIsExpansion = targetTitle.contains("expansion", ignoreCase = true) || targetTitle.contains("espansione", ignoreCase = true) || targetTitle.contains("gilda", ignoreCase = true) || targetTitle.contains("deserto", ignoreCase = true) || targetTitle.contains("pantheon", ignoreCase = true) || targetTitle.contains("agora", ignoreCase = true)
        val candidateIsExpansion = details.expands.isNotEmpty()

        if (targetIsExpansion && !candidateIsExpansion) {
            return -100000
        }

        val candidateNames = mutableListOf<String>()
        details.primaryName?.let { candidateNames.add(it) }
        candidateNames.addAll(details.alternateNames)

        var maxScore = -100000

        for (candName in candidateNames) {
            val normCand = normalize(candName)
            if (normCand.isEmpty()) continue

            var score = when {
                normCand == normTarget -> 10000
                normTarget.contains(normCand) && normCand.length >= 8 -> 5000 + normCand.length
                normCand.contains(normTarget) && normCand.length >= 8 -> 4000 + normCand.length
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
                val wordScore = (overlapRatio * 5000).toInt()
                score = maxOf(score, wordScore)

                val missingWords = targetWords - candWords
                if (missingWords.isNotEmpty()) {
                    score -= missingWords.size * 800
                }
            }

            val targetHasDesert = targetTitle.contains("deserto", ignoreCase = true) || targetTitle.contains("desert", ignoreCase = true)
            val candHasDesert = candName.contains("deserto", ignoreCase = true) || candName.contains("desert", ignoreCase = true)
            if (targetHasDesert && candHasDesert) score += 5000

            val targetHasGuild = targetTitle.contains("gilda", ignoreCase = true) || targetTitle.contains("guild", ignoreCase = true) || targetTitle.contains("spaziale", ignoreCase = true) || targetTitle.contains("spacing", ignoreCase = true)
            val candHasGuild = candName.contains("gilda", ignoreCase = true) || candName.contains("guild", ignoreCase = true) || candName.contains("spaziale", ignoreCase = true) || candName.contains("spacing", ignoreCase = true)
            if (targetHasGuild && candHasGuild) score += 5000

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

        val queries = translateSearchQuery(title)
        val allIds = mutableListOf<Int>()

        for (q in queries) {
            val htmlIds = searchBggHtmlIds(q, excludedId)
            for (id in htmlIds) {
                if (id !in allIds) allIds.add(id)
            }
            val apiIds = searchBggIds(q, excludedId)
            for (id in apiIds) {
                if (id !in allIds) allIds.add(id)
            }
        }

        return allIds
    }

    private fun translateSearchQuery(italianTitle: String): List<String> {
        val clean = italianTitle.replace(Regex("\\(.*?\\)"), "").trim()

        val translations = mapOf(
            "la guerra per arrakis" to "war for arrakis",
            "guerra per arrakis" to "war for arrakis",
            "la gilda spaziale" to "the spacing guild",
            "gilda spaziale" to "spacing guild",
            "la guerra del deserto" to "desert war",
            "guerra del deserto" to "desert war",
            "contrabbandieri" to "smugglers",
            "il grande giubileo" to "the grand jubilee",
            "i re del mare" to "kings of the sea",
            "set rosso" to "red box",
            "set" to "set",
            "rosso" to "red",
        )

        var translated = clean.lowercase(Locale.ROOT)
        for ((itKey, enVal) in translations) {
            translated = translated.replace(itKey, enVal)
        }

        val queries = mutableListOf<String>()
        queries.add(clean)
        if (translated != clean.lowercase(Locale.ROOT)) {
            queries.add(translated)
        }

        if (clean.contains(Regex("[-–—:]"))) {
            val parts = clean.split(Regex("[-–—:]")).map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size >= 2) {
                val mainPart = parts[0]
                val subPart = parts.subList(1, parts.size).joinToString(" ")
                val mainTrans = translations.entries.fold(mainPart.lowercase(Locale.ROOT)) { acc, (k, v) -> acc.replace(k, v) }
                val subTrans = translations.entries.fold(subPart.lowercase(Locale.ROOT)) { acc, (k, v) -> acc.replace(k, v) }
                queries.add("$mainTrans $subTrans")
                queries.add(subTrans)
            }
        }

        return queries.distinct()
    }

    private suspend fun searchBggHtmlIds(query: String, excludedId: Int?): List<Int> {
        val cleanQuery = query.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim().replace(Regex("\\s+"), " ")
        if (cleanQuery.isBlank()) return emptyList()

        val searchUrl = "https://boardgamegeek.com/search/boardgame?q=${java.net.URLEncoder.encode(cleanQuery, "UTF-8")}"
        val htmlText = runCatching { transport.get(searchUrl, XML_HEADERS) }.getOrNull() ?: return emptyList()

        val regex = Regex("""/boardgame(expansion)?/(\d+)/""")
        return regex.findAll(htmlText)
            .mapNotNull { it.groupValues[2].toIntOrNull() }
            .filterNot { it == excludedId }
            .distinct()
            .toList()
    }

    private suspend fun searchBggIds(query: String, excludedId: Int?): List<Int> {
        val cleanQuery = query.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim().replace(Regex("\\s+"), " ")
        if (cleanQuery.isBlank()) return emptyList()

        val searchUrl = endpoint("search") {
            addQueryParameter("query", cleanQuery)
            addQueryParameter("type", "boardgame,boardgameexpansion")
        }
        val xmlText = runCatching { transport.get(searchUrl, XML_HEADERS) }.getOrNull() ?: return emptyList()
        val document = runCatching { parseXml(xmlText) }.getOrNull() ?: return emptyList()

        val items = document.getElementsByTagName("item")
            .let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) } }
            .filter { it.attributes?.getNamedItem("type")?.nodeValue in GAME_TYPES }

        val normQuery = normalize(cleanQuery)

        return items.sortedByDescending { node ->
            val name = (0 until node.childNodes.length).mapNotNull { node.childNodes.item(it) }
                .firstOrNull { it.nodeName == "name" }
                ?.attributes?.getNamedItem("value")?.nodeValue.orEmpty()
            if (normalize(name) == normQuery) 1000 else 0
        }
            .mapNotNull { it.attributes?.getNamedItem("id")?.nodeValue?.toIntOrNull() }
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
            return withDynamicStats(id, bggDetails)
        }

        if (geekGroupApi != null) {
            val ggDetails = runCatching { geekGroupApi?.getGameDetails(id) }.getOrNull()
            if (ggDetails != null) {
                return withDynamicStats(id, ggDetails)
            }
        }

        return runCatching { detailsFromXmlThing(id) }.getOrNull()?.let { withDynamicStats(id, it) }
    }

    private suspend fun withDynamicStats(id: Int, details: BoardGameDetails): BoardGameDetails {
        if (details.bggRating != null && details.bggRank != null) return details
        val statsUrl = "https://api.geekdo.com/api/dynamicinfo?objectid=$id&objecttype=thing"
        val stats = runCatching {
            val response = transport.get(statsUrl, JSON_HEADERS)
            parseDynamicStats(response)
        }.getOrNull() ?: return details
        return details.copy(
            bggRating = details.bggRating ?: stats.first,
            bggRank = details.bggRank ?: stats.second,
        )
    }

    private fun parseDynamicStats(jsonText: String): Pair<Double?, Int?> {
        val item = Json.parseToJsonElement(jsonText.trim()).jsonObject["item"]?.jsonObject
            ?: return null to null
        val stats = item["stats"]?.jsonObject
        val usersRated = stats?.get("usersrated")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val rating = if (usersRated > 0) {
            stats?.get("average")?.jsonPrimitive?.contentOrNull
                ?.toDoubleOrNull()
                ?.takeIf { it > 0.0 && it.isFinite() }
        } else {
            null
        }
        val rank = item["rankinfo"]?.jsonArray
            ?.firstOrNull { element ->
                val rankInfo = element.jsonObject
                rankInfo["rankobjecttype"]?.jsonPrimitive?.contentOrNull == "subtype" &&
                    rankInfo["rankobjectid"]?.jsonPrimitive?.contentOrNull == "1" &&
                    rankInfo["browsesubtype"]?.jsonPrimitive?.contentOrNull == "boardgame"
            }
            ?.jsonObject
            ?.get("rank")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        return rating to rank
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
            ?: item["minplaytime"]?.jsonPrimitive?.intOrNull
            ?: item["playingtime"]?.jsonPrimitive?.intOrNull
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
                val objectId = obj["objectid"]?.jsonPrimitive?.intOrNull
                    ?: obj["objectid"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: return@mapNotNull null
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
            playingTime = null,
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
            addQueryParameter("stats", "1")
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

        val statistics = (0 until children.length).mapNotNull { children.item(it) }
            .firstOrNull { it.nodeName == "statistics" }?.childNodes
        val ratings = statistics?.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) }.firstOrNull { it.nodeName == "ratings" }?.childNodes }
        val average = ratings?.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) }.firstOrNull { it.nodeName == "average" } }
            ?.attributes?.getNamedItem("value")?.nodeValue?.toDoubleOrNull()

        val ranksNode = ratings?.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) }.firstOrNull { it.nodeName == "ranks" } }
        val rank = ranksNode?.childNodes?.let { nodes ->
            (0 until nodes.length).mapNotNull { nodes.item(it) }
                .firstOrNull { it.attributes?.getNamedItem("name")?.nodeValue == "boardgame" }
                ?.attributes?.getNamedItem("value")?.nodeValue?.toIntOrNull()
        }

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
            expands = bggLinks("expandsboardgame"),
            primaryName = primaryName,
            alternateNames = alternateNames,
            bggRating = average,
            bggRank = rank,
        )
    }

    private fun endpoint(path: String, configure: okhttp3.HttpUrl.Builder.() -> Unit): String =
        "$BGG_API/$path".toHttpUrl().newBuilder().apply(configure).build().toString()

    private fun parseXml(xml: String) = DocumentBuilderFactory.newInstance().run {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
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
        val GAME_TYPES = setOf("boardgame", "boardgameexpansion")
        val XML_HEADERS = mapOf("Accept" to "application/xml", "User-Agent" to "MeepleRadar/1.0")
        val JSON_HEADERS = mapOf("Accept" to "application/json", "User-Agent" to "MeepleRadar/1.0")
        val COMMON_WORDS = setOf(
            "game", "edition", "set", "gioco", "rosso", "verde", "blu", "box", "boardgame",
            "expansion", "oscuro", "signore", "la", "del", "della", "delle", "per", "for", "the",
        )
    }
}
