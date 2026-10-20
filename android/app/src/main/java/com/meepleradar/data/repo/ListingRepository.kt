package com.meepleradar.data.repo

import com.meepleradar.data.local.FavoriteStore
import com.meepleradar.data.local.LocalCacheStore
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Product
import com.meepleradar.data.model.Source
import com.meepleradar.data.remote.SupabaseApi
import com.meepleradar.data.remote.UserActionRow
import java.time.LocalDate

interface ListingRepository {
    suspend fun incoming(
        today: LocalDate,
        sourceIds: Set<String>? = null,
        includeUndated: Boolean = true,
        includePriceless: Boolean = true,
        forceRefresh: Boolean = false,
        onProgress: ((percent: Int, message: String) -> Unit)? = null,
    ): List<IncomingItem>

    suspend fun favorites(
        today: LocalDate,
        sourceIds: Set<String>? = null,
        includeUndated: Boolean = true,
        includePriceless: Boolean = true,
        forceRefresh: Boolean = false,
        onProgress: ((percent: Int, message: String) -> Unit)? = null,
    ): List<IncomingItem>

    suspend fun sources(): List<Source>

    suspend fun setAction(key: String, action: Action)

    fun lastDbModifiedTimestamp(): Long
}

class DefaultListingRepository(
    private val api: SupabaseApi,
    private val favoriteStore: FavoriteStore,
    private val localCacheStore: LocalCacheStore? = null,
) : ListingRepository {

    override fun lastDbModifiedTimestamp(): Long =
        localCacheStore?.load()?.lastModifiedTimestamp ?: 0L

    override suspend fun incoming(
        today: LocalDate,
        sourceIds: Set<String>?,
        includeUndated: Boolean,
        includePriceless: Boolean,
        forceRefresh: Boolean,
        onProgress: ((percent: Int, message: String) -> Unit)?,
    ): List<IncomingItem> {
        onProgress?.invoke(10, "Verifica azioni utente…")
        val userActions = runCatching { api.userActions() }.getOrDefault(emptyList())
        val ignored = latestActionsByKey(userActions)
            .filterValues { it == Action.IGNORED }
            .keys
            .toSet()
        return buildItems(ignored, today, sourceIds, includeUndated, includePriceless, forceRefresh, onProgress)
    }

    override suspend fun favorites(
        today: LocalDate,
        sourceIds: Set<String>?,
        includeUndated: Boolean,
        includePriceless: Boolean,
        forceRefresh: Boolean,
        onProgress: ((percent: Int, message: String) -> Unit)?,
    ): List<IncomingItem> {
        onProgress?.invoke(10, "Verifica azioni utente…")
        val userActions = runCatching { api.userActions() }.getOrDefault(emptyList())
        val actionsMap = latestActionsByKey(userActions)
        val ignored = actionsMap.filterValues { it == Action.IGNORED }.keys
        val remoteFavoriteKeys = actionsMap.filterValues { it == Action.FAVORITE }.keys
        val favoriteKeys = favoriteStore.keys() + remoteFavoriteKeys
        return buildItems(ignored, today, sourceIds, includeUndated, includePriceless, forceRefresh, onProgress).filter { it.key in favoriteKeys }
    }

    override suspend fun sources(): List<Source> {
        val cached = localCacheStore?.load()
        if (cached != null && cached.sources.isNotEmpty()) {
            return cached.sources
        }
        return api.sources()
    }

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
        includePriceless: Boolean,
        forceRefresh: Boolean,
        onProgress: ((percent: Int, message: String) -> Unit)? = null,
    ): List<IncomingItem> {
        onProgress?.invoke(25, "Caricamento database locale…")
        var cached = localCacheStore?.load()

        val remoteListings: List<Listing>
        val remoteGames: List<Game>
        val remoteSources: List<Source>
        val remoteProducts: List<Product>

        if (forceRefresh || cached == null || cached.listings.isEmpty()) {
            onProgress?.invoke(40, "Connessione a Supabase…")
            remoteListings = runCatching { api.listings() }.getOrDefault(cached?.listings.orEmpty())
            remoteGames = runCatching { api.games() }.getOrDefault(cached?.games.orEmpty())
            remoteSources = runCatching { api.sources() }.getOrDefault(cached?.sources.orEmpty())
            remoteProducts = runCatching { api.products() }.getOrDefault(cached?.products.orEmpty())

            onProgress?.invoke(85, "Analisi modifiche e differenze…")
            if (localCacheStore != null && remoteListings.isNotEmpty()) {
                val (updatedDb, _) = localCacheStore.saveIfChanged(
                    newListings = remoteListings,
                    newGames = remoteGames,
                    newSources = remoteSources,
                    newProducts = remoteProducts,
                )
                cached = updatedDb
            }
        } else {
            remoteListings = cached.listings
            remoteGames = cached.games
            remoteSources = cached.sources
            remoteProducts = cached.products
        }

        onProgress?.invoke(95, "Elaborazione preferiti e filtri…")

        val listings = cached?.listings?.takeIf { it.isNotEmpty() } ?: remoteListings
        val games = (cached?.games?.takeIf { it.isNotEmpty() } ?: remoteGames).associateBy { it.id }
        val sources = (cached?.sources?.takeIf { it.isNotEmpty() } ?: remoteSources).associateBy { it.id }
        val products = (cached?.products?.takeIf { it.isNotEmpty() } ?: remoteProducts).associateBy { it.id }

        val result = buildIncomingItems(
            listings = listings,
            games = games,
            sources = sources,
            ignoredKeys = ignoredKeys,
            today = today,
            sourceIds = sourceIds,
            includeUndated = includeUndated,
            includePriceless = includePriceless,
            products = products,
        )

        onProgress?.invoke(100, "Sincronizzazione completata!")
        return result
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
