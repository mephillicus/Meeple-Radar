package com.meepleradar.data.repo

import com.meepleradar.data.model.Action
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Source
import com.meepleradar.data.local.FavoriteStore
import com.meepleradar.data.remote.SupabaseApi
import com.meepleradar.data.remote.UserActionRow
import java.time.LocalDate

interface ListingRepository {
    suspend fun incoming(
        today: LocalDate,
        sourceIds: Set<String>? = null,
        includeUndated: Boolean = true,
    ): List<IncomingItem>

    suspend fun favorites(
        today: LocalDate,
        sourceIds: Set<String>? = null,
        includeUndated: Boolean = true,
    ): List<IncomingItem>

    suspend fun sources(): List<Source>

    suspend fun setAction(key: String, action: Action)
}

class DefaultListingRepository(
    private val api: SupabaseApi,
    private val favoriteStore: FavoriteStore,
) : ListingRepository {

    override suspend fun incoming(
        today: LocalDate,
        sourceIds: Set<String>?,
        includeUndated: Boolean,
    ): List<IncomingItem> {
        val userActions = runCatching { api.userActions() }.getOrDefault(emptyList())
        val ignored = latestActionsByKey(userActions)
            .filterValues { it == Action.IGNORED }
            .keys
            .toSet()
        return buildItems(ignored, today, sourceIds, includeUndated)
    }

    override suspend fun favorites(
        today: LocalDate,
        sourceIds: Set<String>?,
        includeUndated: Boolean,
    ): List<IncomingItem> {
        val userActions = runCatching { api.userActions() }.getOrDefault(emptyList())
        val actionsMap = latestActionsByKey(userActions)
        val ignored = actionsMap.filterValues { it == Action.IGNORED }.keys
        val remoteFavoriteKeys = actionsMap.filterValues { it == Action.FAVORITE }.keys
        val favoriteKeys = favoriteStore.keys() + remoteFavoriteKeys
        return buildItems(ignored, today, sourceIds, includeUndated).filter { it.key in favoriteKeys }
    }

    override suspend fun sources(): List<Source> = api.sources()

    override suspend fun setAction(key: String, action: Action) {
        val (kind, id) = parseKey(key)
        when (action) {
            Action.FAVORITE -> favoriteStore.setFavorite(key, true)
            Action.UNFAVORITED -> favoriteStore.setFavorite(key, false)
            Action.IGNORED -> favoriteStore.setFavorite(key, false)
        }

        runCatching {
            val row = when (kind) {
                "game" -> UserActionRow(gameId = id, listingId = null, action = action)
                "listing" -> UserActionRow(gameId = null, listingId = id, action = action)
                "product" -> UserActionRow(gameId = null, listingId = null, action = action, productId = id)
                else -> throw IllegalArgumentException("chiave non valida: $key")
            }
            api.insertAction(row)
        }
    }

    private suspend fun buildItems(
        ignoredKeys: Set<String>,
        today: LocalDate,
        sourceIds: Set<String>?,
        includeUndated: Boolean,
    ): List<IncomingItem> {
        val products = runCatching { api.products() }.getOrDefault(emptyList()).associateBy { it.id }
        return buildIncomingItems(
            listings = api.listings(),
            games = api.games().associateBy { it.id },
            sources = api.sources().associateBy { it.id },
            ignoredKeys = ignoredKeys,
            today = today,
            sourceIds = sourceIds,
            includeUndated = includeUndated,
            products = products,
        )
    }

    private fun parseKey(key: String): Pair<String, Long> {
        val parts = key.split(':', limit = 2)
        require(parts.size == 2 && parts[0].isNotEmpty()) { "chiave non valida: $key" }
        val id = parts[1].toLongOrNull() ?: throw IllegalArgumentException("id non valido in chiave: $key")
        return parts[0] to id
    }

    private fun UserActionRow.keyOrNull(): String? = when {
        gameId != null -> "game:$gameId"
        productId != null -> "product:$productId"
        listingId != null -> "listing:$listingId"
        else -> null
    }

    private fun latestActionsByKey(actions: List<UserActionRow>): Map<String, Action> =
        actions.mapNotNull { row -> row.keyOrNull()?.let { it to row.action } }
            .distinctBy { (key, _) -> key }
            .toMap()
}
