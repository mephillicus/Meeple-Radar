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
    ): Long? = currentGameId

    suspend fun updateGameBggStats(bggId: Int, bggRating: Double?, bggRank: Int?) = Unit
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

    override suspend fun updateGameBggStats(bggId: Int, bggRating: Double?, bggRank: Int?) {
        require(bggId > 0) { "ID BGG non valido: $bggId" }
        val updates = buildMap {
            bggRating?.let { put("bgg_rating", JsonPrimitive(it)) }
            bggRank?.let { put("bgg_rank", JsonPrimitive(it)) }
        }
        require(updates.isNotEmpty()) { "BoardGameGeek non ha restituito voto o rank." }
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
    ): Long {
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

            if (listingId > 0) {
                val listingObj = mapOf("game_id" to JsonPrimitive(targetGameId))
                transport.patch(
                    url = "$base/rest/v1/listing?id=eq.$listingId",
                    headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=minimal")),
                    body = json.encodeToString(listingObj),
                )
            }
            return targetGameId
        } catch (e: Exception) {
            if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                throw IllegalStateException("Permessi Supabase mancanti (HTTP 401/403). Abilita la RLS policy per 'anon' sulle tabelle 'game' e 'listing'.")
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
