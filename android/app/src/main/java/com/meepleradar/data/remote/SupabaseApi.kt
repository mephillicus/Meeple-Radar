package com.meepleradar.data.remote

import com.meepleradar.data.model.Action
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Product
import com.meepleradar.data.model.Source
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

data class UserActionRow(val gameId: Long?, val listingId: Long?, val action: Action, val productId: Long? = null)

interface SupabaseApi {
    suspend fun listings(): List<Listing>

    suspend fun games(): List<Game>

    suspend fun sources(): List<Source>

    suspend fun products(): List<Product> = emptyList()

    suspend fun userActions(): List<UserActionRow>

    suspend fun insertAction(row: UserActionRow)

    suspend fun linkListingToBgg(
        listingId: Long,
        currentGameId: Long?,
        details: BoardGameDetails,
        fallbackTitle: String,
    ): Pair<Long?, Long?> = currentGameId to null

    suspend fun saveGameToDatabase(
        item: com.meepleradar.data.model.IncomingItem,
        selectedListingIdsToLink: List<Long> = emptyList(),
    ): Long?

    suspend fun findCandidateListingsToLink(
        title: String,
        bggId: Int,
    ): List<com.meepleradar.data.model.CandidateListingLink> = emptyList()

    suspend fun updateGameBggStats(
        bggId: Int,
        bggRating: Double?,
        bggRank: Int?,
        description: String? = null,
    ) = Unit
}

class SupabaseRestApi(
    baseUrl: String,
    private val anonKey: String,
    private val transport: HttpTransport,
    private val json: Json,
) : SupabaseApi {

    private val base = baseUrl.trimEnd('/')

    private fun selectUrl(table: String) = "$base/rest/v1/$table?select=*&order=id.asc"

    private fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("apikey", anonKey)
        put("Authorization", "Bearer $anonKey")
        put("Accept", "application/json")
        putAll(extra)
    }

    override suspend fun listings(): List<Listing> =
        fetchAll<ListingDto>(selectUrl("listing"))
            .map { it.toDomain() }

    override suspend fun games(): List<Game> =
        fetchAll<GameDto>(selectUrl("game"))
            .map { it.toDomain() }

    override suspend fun sources(): List<Source> =
        fetchAll<SourceDto>(selectUrl("source"))
            .map { it.toDomain() }

    override suspend fun products(): List<Product> =
        fetchAll<ProductDto>(selectUrl("product"))
            .map { it.toDomain() }

    override suspend fun userActions(): List<UserActionRow> =
        fetchAll<UserActionDto>("$base/rest/v1/user_action?select=*&order=created_at.desc,id.desc")
            .map { it.toDomain() }

    override suspend fun insertAction(row: UserActionRow) {
        transport.post(
            url = "$base/rest/v1/user_action",
            headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "resolution=merge-duplicates,return=minimal")),
            body = json.encodeToString(ActionInsertDto.from(row)),
        )
    }

    override suspend fun updateGameBggStats(
        bggId: Int,
        bggRating: Double?,
        bggRank: Int?,
        description: String?,
    ) {
        require(bggId > 0) { "ID BGG non valido: $bggId" }
        val updates = buildMap {
            bggRating?.let { put("bgg_rating", JsonPrimitive(it)) }
            bggRank?.let { put("bgg_rank", JsonPrimitive(it)) }
            description?.takeIf(String::isNotBlank)?.let { put("description", JsonPrimitive(it)) }
        }
        require(updates.isNotEmpty()) { "BoardGameGeek non ha restituito nessun dato da aggiornare." }
        val response = transport.patch(
            url = "$base/rest/v1/game?bgg_id=eq.$bggId&select=*",
            headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=representation")),
            body = json.encodeToString(updates),
        )
        val updatedGames = json.decodeFromString<List<GameDto>>(response)
        check(updatedGames.any { it.bggId == bggId }) {
            "Nessun gioco associato all'ID BGG $bggId trovato o aggiornabile su Supabase."
        }
    }

    override suspend fun linkListingToBgg(
        listingId: Long,
        currentGameId: Long?,
        details: BoardGameDetails,
        fallbackTitle: String,
    ): Pair<Long?, Long?> {
        try {
            val title = details.primaryName ?: details.englishName ?: fallbackTitle
            val isExpansion = details.expands.isNotEmpty()
            val kindValue = if (isExpansion) "expansion" else "base"
            val gameObj = buildMap {
                put("name", JsonPrimitive(title))
                put("kind", JsonPrimitive(kindValue))
                put("bgg_id", JsonPrimitive(details.bggId))
                put("bgg_rating", details.bggRating?.let(::JsonPrimitive) ?: JsonNull)
                put("bgg_rank", details.bggRank?.let(::JsonPrimitive) ?: JsonNull)
                put("image_url", details.imageUrl?.let(::JsonPrimitive) ?: JsonNull)
                details.description?.takeIf(String::isNotBlank)?.let { put("description", JsonPrimitive(it)) }
            }

            val targetGameId = if (currentGameId != null && currentGameId > 0) {
                transport.patch(
                    url = "$base/rest/v1/game?id=eq.$currentGameId",
                    headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=minimal")),
                    body = json.encodeToString(gameObj),
                )
                currentGameId
            } else {
                val existing = json.decodeFromString<List<GameDto>>(
                    transport.get("$base/rest/v1/game?bgg_id=eq.${details.bggId}&select=*", headers()),
                )
                if (existing.isNotEmpty()) {
                    val existingId = existing.first().id
                    transport.patch(
                        url = "$base/rest/v1/game?id=eq.$existingId",
                        headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=minimal")),
                        body = json.encodeToString(gameObj),
                    )
                    existingId
                } else {
                    val created = json.decodeFromString<List<GameDto>>(
                        transport.post(
                            url = "$base/rest/v1/game",
                            headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=representation")),
                            body = json.encodeToString(gameObj),
                        ),
                    )
                    created.first().id
                }
            }

            var targetProductId: Long? = null

            // Cerca un product già collegato a questo game tramite la colonna game_id
            val productByGameResponse = runCatching {
                transport.get("$base/rest/v1/product?game_id=eq.${targetGameId}&select=*", headers())
            }.getOrNull()
            if (productByGameResponse != null) {
                runCatching {
                    val existingProducts = json.decodeFromString<List<ProductDto>>(productByGameResponse)
                    if (existingProducts.isNotEmpty()) {
                        targetProductId = existingProducts.first().id
                    }
                }
            }

            if (targetProductId == null) {
                // Nessun product trovato per questo game: creane uno nuovo
                val productObj = buildMap {
                    put("title", JsonPrimitive(title))
                    put("url", JsonPrimitive("https://boardgamegeek.com/boardgame/${details.bggId}"))
                    put("game_id", JsonPrimitive(targetGameId))
                    details.imageUrl?.let { put("image_url", JsonPrimitive(it)) }
                    details.description?.takeIf(String::isNotBlank)?.let { put("description", JsonPrimitive(it)) }
                }
                val createdProducts = runCatching {
                    transport.post(
                        url = "$base/rest/v1/product",
                        headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=representation")),
                        body = json.encodeToString(productObj),
                    )
                }.getOrNull()
                if (createdProducts != null) {
                    runCatching {
                        val decoded = json.decodeFromString<List<ProductDto>>(createdProducts)
                        targetProductId = decoded.firstOrNull()?.id
                    }
                }
            }

            runCatching {
                val sourceObj = mapOf("id" to JsonPrimitive("meeple_app"), "name" to JsonPrimitive("MeepleApp"))
                transport.post(
                    url = "$base/rest/v1/source",
                    headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "resolution=merge-duplicates,return=minimal")),
                    body = json.encodeToString(sourceObj),
                )
            }

            if (listingId > 0) {
                val listingObj = buildMap {
                    put("game_id", JsonPrimitive(targetGameId))
                    targetProductId?.let { put("product_id", JsonPrimitive(it)) }
                }
                transport.patch(
                    url = "$base/rest/v1/listing?id=eq.$listingId",
                    headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=minimal")),
                    body = json.encodeToString(listingObj),
                )
            }

            return targetGameId to targetProductId
        } catch (e: Exception) {
            if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                throw IllegalStateException("Permessi Supabase mancanti (HTTP 401/403). Abilita la RLS policy per 'anon' sulle tabelle 'game' e 'listing'.")
            }
            throw e
        }
    }

    override suspend fun saveGameToDatabase(
        item: com.meepleradar.data.model.IncomingItem,
        selectedListingIdsToLink: List<Long>,
    ): Long? {
        try {
            val title = item.title
            val kind = if (item.game?.kind == "expansion") "expansion" else "base"
            val bggId = item.game?.bggId ?: 0
            if (bggId <= 0) throw IllegalStateException("ID BGG non disponibile per il gioco.")

            val itemUrl = "https://boardgamegeek.com/boardgame/$bggId"

            val gameObj = buildMap {
                put("name", JsonPrimitive(title))
                put("kind", JsonPrimitive(kind))
                put("bgg_id", JsonPrimitive(bggId))
                item.game?.bggRating?.let { put("bgg_rating", JsonPrimitive(it)) }
                item.game?.bggRank?.let { put("bgg_rank", JsonPrimitive(it)) }
                item.game?.description?.takeIf(String::isNotBlank)?.let { put("description", JsonPrimitive(it)) }
                item.imageUrl?.let { put("image_url", JsonPrimitive(it)) }
            }

            val existingGame = json.decodeFromString<List<GameDto>>(
                transport.get("$base/rest/v1/game?bgg_id=eq.$bggId&select=*", headers()),
            )
            val createdGame = json.decodeFromString<List<GameDto>>(
                if (existingGame.isNotEmpty()) {
                    val existingId = existingGame.first().id
                    transport.patch(
                        url = "$base/rest/v1/game?id=eq.$existingId&select=*",
                        headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=representation")),
                        body = json.encodeToString(gameObj),
                    )
                } else {
                    transport.post(
                        url = "$base/rest/v1/game",
                        headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=representation")),
                        body = json.encodeToString(gameObj),
                    )
                }
            )
            val targetGameId = createdGame.first().id

            var targetProductId: Long? = null
            val productByGameResponse = runCatching {
                transport.get("$base/rest/v1/product?game_id=eq.${targetGameId}&select=*", headers())
            }.getOrNull()
            if (productByGameResponse != null) {
                runCatching {
                    val existingProducts = json.decodeFromString<List<ProductDto>>(productByGameResponse)
                    if (existingProducts.isNotEmpty()) {
                        targetProductId = existingProducts.first().id
                    }
                }
            }
            if (targetProductId == null) {
                val productObj = buildMap {
                    put("title", JsonPrimitive(title))
                    put("url", JsonPrimitive(itemUrl))
                    put("game_id", JsonPrimitive(targetGameId))
                    item.imageUrl?.let { put("image_url", JsonPrimitive(it)) }
                    item.customDescription?.let { put("description", JsonPrimitive(it)) }
                }
                val createdProducts = runCatching {
                    transport.post(
                        url = "$base/rest/v1/product",
                        headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=representation")),
                        body = json.encodeToString(productObj),
                    )
                }.getOrNull()
                if (createdProducts != null) {
                    runCatching {
                        val decoded = json.decodeFromString<List<ProductDto>>(createdProducts)
                        targetProductId = decoded.firstOrNull()?.id
                    }
                }
            }

            runCatching {
                val sourceObj = mapOf("id" to JsonPrimitive("meeple_app"), "name" to JsonPrimitive("MeepleApp"))
                transport.post(
                    url = "$base/rest/v1/source",
                    headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "resolution=merge-duplicates,return=minimal")),
                    body = json.encodeToString(sourceObj),
                )
            }

            val encodedListingUrl = java.net.URLEncoder.encode(itemUrl, "UTF-8")
            val existingListing = runCatching {
                json.decodeFromString<List<ListingDto>>(
                    transport.get("$base/rest/v1/listing?url=eq.$encodedListingUrl&select=*", headers()),
                )
            }.getOrDefault(emptyList())

            val listingObj = buildMap {
                put("source_id", JsonPrimitive("meeple_app"))
                put("title", JsonPrimitive(title))
                put("url", JsonPrimitive(itemUrl))
                put("game_id", JsonPrimitive(targetGameId))
                targetProductId?.let { put("product_id", JsonPrimitive(it)) }
                put("match_confidence", JsonPrimitive(1.0))
            }

            if (existingListing.isNotEmpty()) {
                val existingListingId = existingListing.first().id
                transport.patch(
                    url = "$base/rest/v1/listing?id=eq.$existingListingId",
                    headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=minimal")),
                    body = json.encodeToString(listingObj),
                )
            } else {
                transport.post(
                    url = "$base/rest/v1/listing",
                    headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=representation")),
                    body = json.encodeToString(listingObj),
                )
            }

            return targetGameId
        } catch (e: Exception) {
            if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                throw IllegalStateException("Permessi Supabase mancanti (HTTP 401/403). Abilita la RLS policy per 'anon' sulle tabelle 'game', 'product', 'listing' e 'source'.")
            }
            throw e
        }
    }

    private suspend inline fun <reified T> fetchAll(url: String): List<T> {
        val rows = mutableListOf<T>()
        var offset = 0
        while (true) {
            val page = json.decodeFromString<List<T>>(
                transport.get(
                    url,
                    headers(mapOf("Range-Unit" to "items", "Range" to "$offset-${offset + PAGE_SIZE - 1}")),
                ),
            )
            rows += page
            if (page.size < PAGE_SIZE) return rows
            offset += PAGE_SIZE
        }
    }

    private companion object {
        const val PAGE_SIZE = 1000
    }
}

private fun JsonElement?.asDoubleOrNull(): Double? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content.toDoubleOrNull()
    else -> null
}

private fun String?.asLocalDateOrNull(): LocalDate? {
    if (isNullOrBlank()) return null
    return try {
        LocalDate.parse(this)
    } catch (e: DateTimeParseException) {
        null
    }
}

private fun String.toAction(): Action = when (lowercase()) {
    "favorite" -> Action.FAVORITE
    "unfavorite" -> Action.UNFAVORITED
    "ignored" -> Action.IGNORED
    else -> Action.valueOf(uppercase())
}

@Serializable
private data class SourceDto(val id: String, val name: String) {
    fun toDomain() = Source(id, name)
}

@Serializable
private data class GameDto(
    val id: Long,
    val name: String,
    val kind: String,
    @SerialName("bgg_id") val bggId: Int? = null,
    @SerialName("bgg_rating") val bggRating: Double? = null,
    @SerialName("bgg_rank") val bggRank: Int? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("description") val description: String? = null,
) {
    fun toDomain() = Game(
        id = id,
        name = name,
        kind = kind,
        bggId = bggId,
        bggRating = bggRating,
        bggRank = bggRank,
        imageUrl = imageUrl,
        description = description,
    )
}

@Serializable
private data class ProductDto(
    val id: Long,
    val title: String? = null,
    val url: String? = null,
    val price: JsonElement? = null,
    val currency: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("description") val description: String? = null,
    val publisher: String? = null,
    @SerialName("game_id") val gameId: Long? = null,
) {
    fun toDomain() = Product(
        id = id,
        title = title,
        imageUrl = imageUrl,
        releaseDate = releaseDate.asLocalDateOrNull(),
        description = description,
        url = url,
        price = price.asDoubleOrNull(),
        currency = currency,
        publisher = publisher,
        gameId = gameId,
    )
}

@Serializable
private data class ListingDto(
    val id: Long,
    @SerialName("source_id") val sourceId: String,
    val title: String,
    val url: String,
    val price: JsonElement? = null,
    val currency: String? = null,
    val availability: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("game_id") val gameId: Long? = null,
    @SerialName("match_confidence") val matchConfidence: Double? = null,
    @SerialName("product_id") val productId: Long? = null,
    @SerialName("description") val description: String? = null,
) {
    fun toDomain() = Listing(
        id = id,
        sourceId = sourceId,
        title = title,
        url = url,
        price = price.asDoubleOrNull(),
        currency = currency,
        availability = availability,
        releaseDate = releaseDate.asLocalDateOrNull(),
        imageUrl = imageUrl,
        gameId = gameId,
        matchConfidence = matchConfidence,
        productId = productId,
        description = description,
    )
}

@Serializable
private data class UserActionDto(
    @SerialName("game_id") val gameId: Long? = null,
    @SerialName("listing_id") val listingId: Long? = null,
    @SerialName("product_id") val productId: Long? = null,
    val action: String,
) {
    fun toDomain() = UserActionRow(gameId, listingId, action.toAction(), productId)
}

@Serializable
private data class ActionInsertDto(
    @SerialName("game_id") val gameId: Long?,
    @SerialName("listing_id") val listingId: Long?,
    @SerialName("product_id") val productId: Long? = null,
    val action: String,
) {
    companion object {
        fun from(row: UserActionRow) = ActionInsertDto(
            gameId = row.gameId,
            listingId = row.listingId,
            productId = row.productId,
            action = when (row.action) {
                Action.FAVORITE -> "favorite"
                Action.UNFAVORITED -> "unfavorite"
                Action.IGNORED -> "ignored"
            },
        )
    }
}
