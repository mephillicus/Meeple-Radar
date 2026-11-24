package com.meepleradar.data.remote

import com.meepleradar.data.model.Action
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.ListingBestPrice
import com.meepleradar.data.model.PriceHistoryEntry
import com.meepleradar.data.model.Product
import com.meepleradar.data.model.Source
import com.meepleradar.data.model.UserAlert
import com.meepleradar.data.model.UserAlertType
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class UserActionRow(val gameId: Long?, val listingId: Long?, val action: Action, val productId: Long? = null)

interface SupabaseApi {
    suspend fun listings(): List<Listing>

    suspend fun listingsUpdatedSince(sinceIso: String): List<Listing> = listings()

    suspend fun games(): List<Game>

    suspend fun gamesUpdatedSince(sinceIso: String): List<Game> = games()

    suspend fun sources(): List<Source>

    suspend fun products(): List<Product> = emptyList()

    suspend fun productsUpdatedSince(sinceIso: String): List<Product> = products()

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

    suspend fun listingBestPrices(listingIds: Collection<Long>? = null): List<ListingBestPrice> = emptyList()

    suspend fun listingPriceHistory(listingIds: Collection<Long>, limit: Int = 40): List<PriceHistoryEntry> = emptyList()

    suspend fun userAlerts(limit: Int = 50): List<UserAlert> = emptyList()

    suspend fun markAlertRead(alertId: Long, readAtIso: String) = Unit

    suspend fun markAllAlertsRead(alertIds: Collection<Long>, readAtIso: String) = Unit
}

class SupabaseRestApi(
    baseUrl: String,
    private val anonKey: String,
    private val transport: HttpTransport,
    private val json: Json,
) : SupabaseApi {

    private val base = baseUrl.trimEnd('/')

    private fun selectUrl(table: String) = "$base/rest/v1/$table?select=*&order=id.asc"

    private fun selectUpdatedSinceUrl(table: String, sinceIso: String): String {
        val encoded = URLEncoder.encode(sinceIso, "UTF-8")
        return "$base/rest/v1/$table?select=*&updated_at=gt.$encoded&order=id.asc"
    }

    private fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("apikey", anonKey)
        put("Authorization", "Bearer $anonKey")
        put("Accept", "application/json")
        putAll(extra)
    }

    override suspend fun listings(): List<Listing> =
        fetchAll<ListingDto>(selectUrl("listing"))
            .map { it.toDomain() }

    override suspend fun listingsUpdatedSince(sinceIso: String): List<Listing> =
        fetchAll<ListingDto>(selectUpdatedSinceUrl("listing", sinceIso))
            .map { it.toDomain() }

    override suspend fun games(): List<Game> =
        fetchAll<GameDto>(selectUrl("game"))
            .map { it.toDomain() }

    override suspend fun gamesUpdatedSince(sinceIso: String): List<Game> =
        fetchAll<GameDto>(selectUpdatedSinceUrl("game", sinceIso))
            .map { it.toDomain() }

    override suspend fun sources(): List<Source> =
        fetchAll<SourceDto>(selectUrl("source"))
            .map { it.toDomain() }

    override suspend fun products(): List<Product> =
        fetchAll<ProductDto>(selectUrl("product"))
            .map { it.toDomain() }

    override suspend fun productsUpdatedSince(sinceIso: String): List<Product> =
        fetchAll<ProductDto>(selectUpdatedSinceUrl("product", sinceIso))
            .map { it.toDomain() }

    override suspend fun userActions(): List<UserActionRow> =
        fetchAll<UserActionDto>("$base/rest/v1/user_action?select=*&order=created_at.desc,id.desc")
            .map { it.toDomain() }

    override suspend fun listingBestPrices(listingIds: Collection<Long>?): List<ListingBestPrice> {
        val url = if (listingIds == null) {
            "$base/rest/v1/listing_best_price?select=*&price_drop_pct=gt.0&order=listing_id.asc"
        } else {
            val cleanIds = listingIds.filter { it > 0L }.distinct()
            if (cleanIds.isEmpty()) return emptyList()
            "$base/rest/v1/listing_best_price?select=*&listing_id=in.(${cleanIds.joinToString(",")})"
        }
        return try {
            fetchAll<ListingBestPriceDto>(url).map { it.toDomain() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun listingPriceHistory(
        listingIds: Collection<Long>,
        limit: Int,
    ): List<PriceHistoryEntry> {
        val cleanIds = listingIds.filter { it > 0L }.distinct()
        if (cleanIds.isEmpty()) return emptyList()
        val url = "$base/rest/v1/listing_price_history?select=*&listing_id=in.(${cleanIds.joinToString(",")})&order=recorded_at.desc,id.desc&limit=$limit"
        return try {
            val response = transport.get(url, headers())
            json.decodeFromString<List<PriceHistoryDto>>(response).map { it.toDomain() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun userAlerts(limit: Int): List<UserAlert> {
        val url = "$base/rest/v1/user_alert?select=*&order=created_at.desc,id.desc&limit=$limit"
        return try {
            val response = transport.get(url, headers())
            json.decodeFromString<List<UserAlertDto>>(response).mapNotNull { it.toDomainOrNull() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun markAlertRead(alertId: Long, readAtIso: String) {
        transport.patch(
            url = "$base/rest/v1/user_alert?id=eq.$alertId",
            headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=minimal")),
            body = json.encodeToString(mapOf("read_at" to JsonPrimitive(readAtIso))),
        )
    }

    override suspend fun markAllAlertsRead(alertIds: Collection<Long>, readAtIso: String) {
        val cleanIds = alertIds.filter { it > 0L }.distinct()
        if (cleanIds.isEmpty()) return
        transport.patch(
            url = "$base/rest/v1/user_alert?id=in.(${cleanIds.joinToString(",")})",
            headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=minimal")),
            body = json.encodeToString(mapOf("read_at" to JsonPrimitive(readAtIso))),
        )
    }

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
        val normalizedDescription = description?.takeIf(String::isNotBlank)
        require(bggRating != null || bggRank != null || normalizedDescription != null) {
            "BoardGameGeek non ha restituito nessun dato da aggiornare."
        }

        val rpcPayload = buildMap<String, JsonElement> {
            put("p_bgg_id", JsonPrimitive(bggId))
            put("p_bgg_rating", bggRating?.let(::JsonPrimitive) ?: JsonNull)
            put("p_bgg_rank", bggRank?.let(::JsonPrimitive) ?: JsonNull)
            put("p_description", normalizedDescription?.let(::JsonPrimitive) ?: JsonNull)
        }

        try {
            val response = transport.post(
                url = "$base/rest/v1/rpc/update_game_bgg_stats",
                headers = headers(mapOf("Content-Type" to "application/json")),
                body = json.encodeToString(rpcPayload),
            )
            val updatedId = runCatching {
                json.decodeFromString<JsonPrimitive>(response).content.toLongOrNull()
            }.getOrNull()
            check(updatedId != null && updatedId > 0L) {
                "Nessun gioco associato all'ID BGG $bggId trovato o aggiornabile su Supabase."
            }
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isRpcMissingError(e)) {
                if (e.message?.contains("No game found", ignoreCase = true) == true) {
                    throw IllegalStateException(
                        "Nessun gioco associato all'ID BGG $bggId trovato o aggiornabile su Supabase.",
                        e,
                    )
                }
                throw e
            }
        }

        // Fallback legacy quando la migrazione 0013_app_write_rpcs.sql non è ancora attiva.
        val updates = buildMap<String, JsonElement> {
            bggRating?.let { put("bgg_rating", JsonPrimitive(it)) }
            bggRank?.let { put("bgg_rank", JsonPrimitive(it)) }
            normalizedDescription?.let { put("description", JsonPrimitive(it)) }
        }
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

    private suspend fun upsertMeepleAppListing(
        bggId: Int,
        title: String,
        url: String,
        imageUrl: String?,
        gameId: Long,
        productId: Long,
    ) {
        val externalId = "bgg:$bggId"
        val legacyListing = json.decodeFromString<List<ListingDto>>(
            transport.get(
                "$base/rest/v1/listing?source_id=eq.meeple_app&url=eq.${URLEncoder.encode(url, "UTF-8")}&select=*",
                headers(),
            ),
        ).firstOrNull()
        val listingObj = buildMap {
            put("source_id", JsonPrimitive("meeple_app"))
            put("external_id", JsonPrimitive(externalId))
            put("title", JsonPrimitive(title))
            put("url", JsonPrimitive(url))
            put("image_url", imageUrl?.let(::JsonPrimitive) ?: JsonNull)
            put("game_id", JsonPrimitive(gameId))
            put("product_id", JsonPrimitive(productId))
            put("match_confidence", JsonPrimitive(1.0))
        }
        if (legacyListing != null) {
            transport.patch(
                url = "$base/rest/v1/listing?id=eq.${legacyListing.id}",
                headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=minimal")),
                body = json.encodeToString(listingObj),
            )
        } else {
            transport.post(
                url = "$base/rest/v1/listing?on_conflict=source_id,external_id",
                headers = headers(mapOf(
                    "Content-Type" to "application/json",
                    "Prefer" to "resolution=merge-duplicates,return=representation",
                )),
                body = json.encodeToString(listingObj),
            )
        }
    }

    override suspend fun linkListingToBgg(
        listingId: Long,
        currentGameId: Long?,
        details: BoardGameDetails,
        fallbackTitle: String,
    ): Pair<Long?, Long?> {
        val title = details.primaryName ?: details.englishName ?: fallbackTitle
        val kindValue = if (details.expands.isNotEmpty()) "expansion" else "base"
        val rpcPayload = buildMap<String, JsonElement> {
            put("p_listing_id", JsonPrimitive(listingId))
            put("p_bgg_id", JsonPrimitive(details.bggId))
            put("p_title", JsonPrimitive(title))
            put("p_current_game_id", currentGameId?.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull)
            put("p_kind", JsonPrimitive(kindValue))
            put("p_bgg_rating", details.bggRating?.let(::JsonPrimitive) ?: JsonNull)
            put("p_bgg_rank", details.bggRank?.let(::JsonPrimitive) ?: JsonNull)
            put("p_image_url", details.imageUrl?.let(::JsonPrimitive) ?: JsonNull)
            put("p_description", details.description?.takeIf(String::isNotBlank)?.let(::JsonPrimitive) ?: JsonNull)
        }

        try {
            val rpcResponse = transport.post(
                url = "$base/rest/v1/rpc/link_listing_to_bgg",
                headers = headers(mapOf("Content-Type" to "application/json")),
                body = json.encodeToString(rpcPayload),
            )
            val rows = json.decodeFromString<List<LinkListingRpcRow>>(rpcResponse)
            val first = rows.firstOrNull()
                ?: throw IllegalStateException("La RPC link_listing_to_bgg non ha restituito nessun risultato.")
            return first.gameId to first.productId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isRpcMissingError(e)) {
                return linkListingToBggLegacy(listingId, currentGameId, details, fallbackTitle)
            }
            if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                throw IllegalStateException("Permessi Supabase mancanti (HTTP 401/403). Verifica le policy RLS per 'anon' sulle tabelle 'game', 'product' e 'listing'.")
            }
            throw e
        }
    }

    private suspend fun linkListingToBggLegacy(
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

            val productByGameResponse = transport.get(
                "$base/rest/v1/product?game_id=eq.${targetGameId}&select=*",
                headers(),
            )
            val existingProductId = json.decodeFromString<List<ProductDto>>(productByGameResponse)
                .firstOrNull()
                ?.id
            val targetProductId = existingProductId ?: run {
                val rpcResponse = try {
                    transport.post(
                        url = "$base/rest/v1/rpc/upsert_product_for_game",
                        headers = headers(mapOf("Content-Type" to "application/json")),
                        body = json.encodeToString(buildMap {
                            put("p_game_id", JsonPrimitive(targetGameId))
                            put("p_title", JsonPrimitive(title))
                            put("p_url", JsonNull)
                            put("p_image_url", details.imageUrl?.let(::JsonPrimitive) ?: JsonNull)
                            put(
                                "p_description",
                                details.description?.takeIf(String::isNotBlank)?.let(::JsonPrimitive) ?: JsonNull,
                            )
                        }),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw productCreationError(e)
                }
                val productId = runCatching {
                    json.decodeFromString<JsonPrimitive>(rpcResponse).content.toLongOrNull()
                }.getOrNull()
                productId?.takeIf { it > 0 } ?: throw productCreationError()
            }

            if (listingId > 0) {
                transport.patch(
                    url = "$base/rest/v1/listing?id=eq.$listingId",
                    headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=minimal")),
                    body = json.encodeToString(mapOf(
                        "game_id" to JsonPrimitive(targetGameId),
                        "product_id" to JsonPrimitive(targetProductId),
                    )),
                )
            } else {
                upsertMeepleAppListing(
                    bggId = details.bggId,
                    title = title,
                    url = "https://boardgamegeek.com/boardgame/${details.bggId}",
                    imageUrl = details.imageUrl,
                    gameId = targetGameId,
                    productId = targetProductId,
                )
            }

            return targetGameId to targetProductId
        } catch (e: Exception) {
            if (e.message?.contains("0010_add_upsert_product_for_game.sql") == true) {
                throw e
            }
            if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                throw IllegalStateException("Permessi Supabase mancanti (HTTP 401/403). Verifica le policy RLS per 'anon' sulle tabelle 'game', 'product' e 'listing'.")
            }

            throw e
        }
    }

    private fun productCreationError(cause: Throwable? = null) = IllegalStateException(
        "Impossibile creare o recuperare il prodotto associato al gioco. " +
            "Verifica che la migrazione Supabase 0010_add_upsert_product_for_game.sql sia stata applicata " +
            "e che la funzione public.upsert_product_for_game sia disponibile. " +
            (cause?.message?.let { "Dettaglio: $it" } ?: "La funzione non ha restituito un ID prodotto valido."),
        cause,
    )

    override suspend fun saveGameToDatabase(
        item: com.meepleradar.data.model.IncomingItem,
        selectedListingIdsToLink: List<Long>,
    ): Long? {
        val title = item.title
        val kind = if (item.game?.kind == "expansion") "expansion" else "base"
        val bggId = item.game?.bggId ?: 0
        if (bggId <= 0) throw IllegalStateException("ID BGG non disponibile per il gioco.")

        val rpcPayload = buildMap<String, JsonElement> {
            put("p_bgg_id", JsonPrimitive(bggId))
            put("p_title", JsonPrimitive(title))
            put("p_kind", JsonPrimitive(kind))
            put("p_bgg_rating", item.game?.bggRating?.let(::JsonPrimitive) ?: JsonNull)
            put("p_bgg_rank", item.game?.bggRank?.let(::JsonPrimitive) ?: JsonNull)
            put("p_image_url", item.imageUrl?.let(::JsonPrimitive) ?: JsonNull)
            put("p_game_description", item.game?.description?.takeIf(String::isNotBlank)?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "p_product_description",
                (item.customDescription ?: item.game?.description)
                    ?.takeIf(String::isNotBlank)
                    ?.let(::JsonPrimitive) ?: JsonNull,
            )
            put("p_listing_ids_to_link", JsonArray(selectedListingIdsToLink.distinct().map(::JsonPrimitive)))
        }

        try {
            val rpcResponse = transport.post(
                url = "$base/rest/v1/rpc/save_game_to_database",
                headers = headers(mapOf("Content-Type" to "application/json")),
                body = json.encodeToString(rpcPayload),
            )
            val gameId = runCatching {
                json.decodeFromString<JsonPrimitive>(rpcResponse).content.toLongOrNull()
            }.getOrNull()
            return gameId?.takeIf { it > 0L }
                ?: throw IllegalStateException("La RPC save_game_to_database non ha restituito un ID gioco valido.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isRpcMissingError(e)) {
                return saveGameToDatabaseLegacy(item, selectedListingIdsToLink)
            }
            if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                throw IllegalStateException("Permessi Supabase mancanti (HTTP 401/403). Verifica le policy RLS per 'anon' sulle tabelle 'game', 'product' e 'listing'.")
            }
            throw e
        }
    }

    private suspend fun saveGameToDatabaseLegacy(
        item: com.meepleradar.data.model.IncomingItem,
        selectedListingIdsToLink: List<Long>,
    ): Long {
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

            val productRpcResponse = transport.post(
                url = "$base/rest/v1/rpc/upsert_product_for_game",
                headers = headers(mapOf("Content-Type" to "application/json")),
                body = json.encodeToString(buildMap {
                    put("p_game_id", JsonPrimitive(targetGameId))
                    put("p_title", JsonPrimitive(title))
                    put("p_url", JsonPrimitive(itemUrl))
                    put("p_image_url", item.imageUrl?.let(::JsonPrimitive) ?: JsonNull)
                    put(
                        "p_description",
                        (item.customDescription ?: item.game?.description)
                            ?.takeIf(String::isNotBlank)
                            ?.let(::JsonPrimitive) ?: JsonNull,
                    )
                }),
            )
            val targetProductId = runCatching {
                json.decodeFromString<JsonPrimitive>(productRpcResponse).content.toLongOrNull()
            }.getOrNull()?.takeIf { it > 0 } ?: throw productCreationError()

            selectedListingIdsToLink.distinct().forEach { selectedListingId ->
                transport.patch(
                    url = "$base/rest/v1/listing?id=eq.$selectedListingId",
                    headers = headers(mapOf("Content-Type" to "application/json", "Prefer" to "return=minimal")),
                    body = json.encodeToString(mapOf(
                        "game_id" to JsonPrimitive(targetGameId),
                        "product_id" to JsonPrimitive(targetProductId),
                    )),
                )
            }

            upsertMeepleAppListing(
                bggId = bggId,
                title = title,
                url = itemUrl,
                imageUrl = item.imageUrl,
                gameId = targetGameId,
                productId = targetProductId,
            )

            return targetGameId
        } catch (e: Exception) {
            if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                throw IllegalStateException("Permessi Supabase mancanti (HTTP 401/403). Verifica le policy RLS per 'anon' sulle tabelle 'game', 'product' e 'listing'.")
            }
            throw e
        }
    }

    private fun isRpcMissingError(e: Exception): Boolean {
        val msg = e.message ?: return false
        return msg.contains("HTTP 404") || msg.contains("PGRST202")
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
    "seen" -> Action.SEEN
    else -> Action.valueOf(uppercase())
}

@Serializable
private data class LinkListingRpcRow(
    @SerialName("game_id") val gameId: Long,
    @SerialName("product_id") val productId: Long,
)

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
    @SerialName("updated_at") val updatedAt: String? = null,
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
        updatedAt = updatedAt,
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
    @SerialName("reprint_date") val reprintDate: String? = null,
    @SerialName("description") val description: String? = null,
    val publisher: String? = null,
    @SerialName("game_id") val gameId: Long? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
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
        updatedAt = updatedAt,
        reprintDate = reprintDate.asLocalDateOrNull(),
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
    @SerialName("reprint_date") val reprintDate: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("game_id") val gameId: Long? = null,
    @SerialName("match_confidence") val matchConfidence: Double? = null,
    @SerialName("product_id") val productId: Long? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("first_seen_at") val firstSeenAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
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
        firstSeenAt = firstSeenAt,
        updatedAt = updatedAt,
        reprintDate = reprintDate.asLocalDateOrNull(),
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
                Action.SEEN -> "seen"
            },
        )
    }
}

@Serializable
private data class ListingBestPriceDto(
    @SerialName("listing_id") val listingId: Long,
    @SerialName("current_price") val currentPrice: JsonElement? = null,
    val currency: String? = null,
    @SerialName("current_availability") val currentAvailability: String? = null,
    @SerialName("min_price") val minPrice: JsonElement? = null,
    @SerialName("previous_price") val previousPrice: JsonElement? = null,
    @SerialName("price_drop_pct") val priceDropPct: JsonElement? = null,
    @SerialName("recorded_at") val recordedAt: String? = null,
) {
    fun toDomain() = ListingBestPrice(
        listingId = listingId,
        currentPrice = currentPrice.asDoubleOrNull(),
        currency = currency,
        currentAvailability = currentAvailability,
        minPrice = minPrice.asDoubleOrNull(),
        previousPrice = previousPrice.asDoubleOrNull(),
        priceDropPct = priceDropPct.asDoubleOrNull(),
        recordedAt = recordedAt,
    )
}

@Serializable
private data class PriceHistoryDto(
    val id: Long,
    @SerialName("listing_id") val listingId: Long,
    val price: JsonElement? = null,
    val currency: String? = null,
    val availability: String? = null,
    @SerialName("recorded_at") val recordedAt: String,
) {
    fun toDomain() = PriceHistoryEntry(
        id = id,
        listingId = listingId,
        price = price.asDoubleOrNull(),
        currency = currency,
        availability = availability,
        recordedAt = recordedAt,
    )
}

@Serializable
private data class UserAlertDto(
    val id: Long,
    @SerialName("alert_type") val alertType: String,
    @SerialName("game_id") val gameId: Long? = null,
    @SerialName("product_id") val productId: Long? = null,
    @SerialName("listing_id") val listingId: Long? = null,
    val title: String,
    val payload: JsonObject? = null,
    @SerialName("dedupe_key") val dedupeKey: String,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("created_at") val createdAt: String,
) {
    fun toDomainOrNull(): UserAlert? {
        val type = when (alertType.lowercase(Locale.ROOT)) {
            "back_in_stock" -> UserAlertType.BACK_IN_STOCK
            "price_drop" -> UserAlertType.PRICE_DROP
            "new_expansion" -> UserAlertType.NEW_EXPANSION
            else -> return null
        }
        val bggId = payload?.get("bggId").asIntOrNull()
            ?: payload?.get("bgg_id").asIntOrNull()
        val priceDropPct = payload?.get("price_drop_pct").asDoubleOrNull()
            ?: payload?.get("dropPct").asDoubleOrNull()
        return UserAlert(
            id = id,
            alertType = type,
            gameId = gameId,
            productId = productId,
            listingId = listingId,
            title = title,
            detailText = formatAlertDetail(type, payload),
            dedupeKey = dedupeKey,
            readAt = readAt,
            createdAt = createdAt,
            bggId = bggId,
            priceDropPct = priceDropPct,
        )
    }
}

private fun formatAlertDetail(type: UserAlertType, payload: JsonObject?): String? {
    if (payload == null) return null
    val message = payload["message"].asStringOrNull()
    if (!message.isNullOrBlank()) return message
    val store = payload["source_name"].asStringOrNull()
        ?: payload["sourceName"].asStringOrNull()
        ?: payload["store"].asStringOrNull()
        ?: payload["source_id"].asStringOrNull()
        ?: payload["sourceId"].asStringOrNull()
    return when (type) {
        UserAlertType.PRICE_DROP -> {
            val prev = payload["previous_price"].asDoubleOrNull()
                ?: payload["previousPrice"].asDoubleOrNull()
            val curr = payload["current_price"].asDoubleOrNull()
                ?: payload["currentPrice"].asDoubleOrNull()
                ?: payload["price"].asDoubleOrNull()
            val pct = payload["price_drop_pct"].asDoubleOrNull()
                ?: payload["dropPct"].asDoubleOrNull()
            buildString {
                if (!store.isNullOrBlank()) append("$store: ")
                if (prev != null && curr != null) {
                    append(String.format(Locale.ITALY, "€%.2f → €%.2f", prev, curr))
                } else if (curr != null) {
                    append(String.format(Locale.ITALY, "€%.2f", curr))
                }
                if (pct != null && pct > 0.0) {
                    append(" (-${pct.toInt()}%)")
                }
            }.ifBlank { null }
        }
        UserAlertType.BACK_IN_STOCK -> {
            if (!store.isNullOrBlank()) "Tornato disponibile su $store" else "Tornato disponibile"
        }
        UserAlertType.NEW_EXPANSION -> {
            val baseName = payload["base_game_name"].asStringOrNull()
                ?: payload["baseGameName"].asStringOrNull()
            when {
                !baseName.isNullOrBlank() && !store.isNullOrBlank() -> "Espansione per $baseName su $store"
                !baseName.isNullOrBlank() -> "Nuova espansione per $baseName"
                !store.isNullOrBlank() -> "Nuova espansione rilevata su $store"
                else -> "Nuova espansione rilevata"
            }
        }
    }
}

private fun JsonElement?.asStringOrNull(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content.takeIf(String::isNotBlank)
    else -> null
}

private fun JsonElement?.asIntOrNull(): Int? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content.toIntOrNull()
    else -> null
}
