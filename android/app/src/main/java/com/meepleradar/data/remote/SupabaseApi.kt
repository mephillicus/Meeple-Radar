package com.meepleradar.data.remote

import com.meepleradar.data.model.Action
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Source
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

data class UserActionRow(val gameId: Long?, val listingId: Long?, val action: Action)

interface SupabaseApi {
    suspend fun listings(): List<Listing>

    suspend fun games(): List<Game>

    suspend fun sources(): List<Source>

    suspend fun userActions(): List<UserActionRow>

    suspend fun insertAction(row: UserActionRow)
}

class SupabaseRestApi(
    baseUrl: String,
    private val anonKey: String,
    private val transport: HttpTransport,
    private val json: Json,
) : SupabaseApi {

    private val base = baseUrl.trimEnd('/')

    private fun selectUrl(table: String) = "$base/rest/v1/$table?select=*"

    private fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("apikey", anonKey)
        put("Authorization", "Bearer $anonKey")
        put("Accept", "application/json")
        putAll(extra)
    }

    override suspend fun listings(): List<Listing> =
        json.decodeFromString<List<ListingDto>>(transport.get(selectUrl("listing"), headers()))
            .map { it.toDomain() }

    override suspend fun games(): List<Game> =
        json.decodeFromString<List<GameDto>>(transport.get(selectUrl("game"), headers()))
            .map { it.toDomain() }

    override suspend fun sources(): List<Source> =
        json.decodeFromString<List<SourceDto>>(transport.get(selectUrl("source"), headers()))
            .map { it.toDomain() }

    override suspend fun userActions(): List<UserActionRow> =
        json.decodeFromString<List<UserActionDto>>(transport.get(selectUrl("user_action"), headers()))
            .map { it.toDomain() }

    override suspend fun insertAction(row: UserActionRow) {
        transport.post(
            url = "$base/rest/v1/user_action",
            headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=minimal")),
            body = json.encodeToString(ActionInsertDto.from(row)),
        )
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
) {
    fun toDomain() = Game(
        id = id,
        name = name,
        kind = kind,
        bggId = bggId,
        bggRating = bggRating,
        bggRank = bggRank,
        imageUrl = imageUrl,
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
    )
}

@Serializable
private data class UserActionDto(
    @SerialName("game_id") val gameId: Long? = null,
    @SerialName("listing_id") val listingId: Long? = null,
    val action: String,
) {
    fun toDomain() = UserActionRow(gameId, listingId, action.toAction())
}

@Serializable
private data class ActionInsertDto(
    @SerialName("game_id") val gameId: Long?,
    @SerialName("listing_id") val listingId: Long?,
    val action: String,
) {
    companion object {
        fun from(row: UserActionRow) = ActionInsertDto(
            gameId = row.gameId,
            listingId = row.listingId,
            action = row.action.name.lowercase(),
        )
    }
}
