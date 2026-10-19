package com.meepleradar.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class GeekGroupItemDto(
    val id: Int? = null,
    @SerialName("bgg_id") val bggId: Int? = null,
    val name: String? = null,
    val image: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("year_published") val yearPublished: Int? = null,
    @SerialName("min_players") val minPlayers: Int? = null,
    @SerialName("max_players") val maxPlayers: Int? = null,
    @SerialName("playing_time") val playingTime: Int? = null,
    @SerialName("min_age") val minAge: Int? = null,
) {
    val gameId: Int? get() = bggId ?: id
    val coverUrl: String? get() = imageUrl ?: image
}

interface GeekGroupApi {
    suspend fun getUserCollection(username: String): List<GeekGroupItemDto>
    suspend fun getGameDetails(bggId: Int): BoardGameDetails?
}

class GeekGroupRestApi(
    private val transport: HttpTransport,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : GeekGroupApi {

    override suspend fun getUserCollection(username: String): List<GeekGroupItemDto> {
        val cleanName = username.trim()
        if (cleanName.isBlank()) return emptyList()
        val url = "https://geekgroup.app/api/users/$cleanName/collection"
        return try {
            val response = transport.get(url, HEADERS)
            if (response.isBlank()) emptyList() else json.decodeFromString<List<GeekGroupItemDto>>(response)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getGameDetails(bggId: Int): BoardGameDetails? {
        val url = "https://geekgroup.app/api/games/$bggId"
        return try {
            val responseText = transport.get(url, HEADERS)
            if (responseText.isBlank()) return null
            parseGeekGroupGameJson(bggId, responseText)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseGeekGroupGameJson(id: Int, jsonText: String): BoardGameDetails? {
        val root = runCatching { Json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return null

        val imageUrl = root["image"]?.jsonPrimitive?.contentOrNull
            ?: root["image_url"]?.jsonPrimitive?.contentOrNull

        val description = root["description"]?.jsonPrimitive?.contentOrNull
        val yearPublished = root["year_published"]?.jsonPrimitive?.intOrNull
            ?: root["yearpublished"]?.jsonPrimitive?.intOrNull
        val minPlayers = root["min_players"]?.jsonPrimitive?.intOrNull
            ?: root["minplayers"]?.jsonPrimitive?.intOrNull
        val maxPlayers = root["max_players"]?.jsonPrimitive?.intOrNull
            ?: root["maxplayers"]?.jsonPrimitive?.intOrNull
        val playingTime = root["playing_time"]?.jsonPrimitive?.intOrNull
            ?: root["playingtime"]?.jsonPrimitive?.intOrNull
        val minAge = root["min_age"]?.jsonPrimitive?.intOrNull
            ?: root["minage"]?.jsonPrimitive?.intOrNull

        fun parseStringArray(key: String): List<String> = runCatching {
            root[key]?.jsonArray
                ?.mapNotNull { el ->
                    when {
                        el.jsonObject.containsKey("name") -> el.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                        else -> el.jsonPrimitive.contentOrNull
                    }
                }
                ?.distinct()
        }.getOrNull() ?: emptyList()

        return BoardGameDetails(
            bggId = id,
            imageUrl = imageUrl,
            description = description,
            yearPublished = yearPublished,
            minPlayers = minPlayers,
            maxPlayers = maxPlayers,
            playingTime = playingTime,
            minAge = minAge,
            designers = parseStringArray("designers"),
            categories = parseStringArray("categories"),
            mechanics = parseStringArray("mechanics"),
        )
    }

    private companion object {
        val HEADERS = mapOf("Accept" to "application/json", "User-Agent" to "MeepleRadar/1.0")
    }
}
