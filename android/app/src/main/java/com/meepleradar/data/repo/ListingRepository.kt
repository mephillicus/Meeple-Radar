package com.meepleradar.data.repo

import com.meepleradar.data.local.FavoriteStore
import com.meepleradar.data.local.LocalCacheStore
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.GameSavePreview
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Product
import com.meepleradar.data.model.Source
import com.meepleradar.data.remote.SupabaseApi
import com.meepleradar.data.remote.UserActionRow
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    suspend fun newsItems(today: LocalDate): List<IncomingItem>

    suspend fun preorderItems(today: LocalDate): List<IncomingItem>

    suspend fun findItem(key: String, today: LocalDate): IncomingItem?

    suspend fun sources(): List<Source>

    suspend fun setAction(key: String, action: Action)

    fun seenKeys(): Set<String> = emptySet()

    fun markLocalSeen(key: String, seen: Boolean = true) {}

    fun lastDbModifiedTimestamp(): Long

    fun newlyAddedCount(): Int

    fun newlyAddedCountFlow(): StateFlow<Int>

    fun preorderCount(): Int

    suspend fun linkListingToBgg(
        listingId: Long,
        currentGameId: Long?,
        details: com.meepleradar.data.remote.BoardGameDetails,
        fallbackTitle: String,
    ): Pair<Long?, Long?> = currentGameId to null

    suspend fun previewGameSave(item: IncomingItem): GameSavePreview

    suspend fun saveGameToDatabase(
        item: IncomingItem,
        selectedListingIdsToLink: List<Long>
    ): Long?

    suspend fun updateGameBggStats(
        bggId: Int,
        bggRating: Double?,
        bggRank: Int?,
        description: String? = null,
    ) = Unit
}

class DefaultListingRepository(
    private val api: SupabaseApi,
    private val favoriteStore: FavoriteStore,
    private val localCacheStore: LocalCacheStore? = null,
    private val bggCollectionStore: com.meepleradar.data.local.BggCollectionStore? = null,
    private val seenStore: com.meepleradar.data.local.SeenStore? = null,
) : ListingRepository {

    private val _newlyAddedCount = MutableStateFlow(localCacheStore?.load()?.newlyAddedCount ?: 0)

    override fun lastDbModifiedTimestamp(): Long =
        localCacheStore?.load()?.lastModifiedTimestamp ?: 0L

    override fun newlyAddedCount(): Int = _newlyAddedCount.value

    override fun newlyAddedCountFlow(): StateFlow<Int> = _newlyAddedCount.asStateFlow()

    override fun preorderCount(): Int {
        val cached = localCacheStore?.load() ?: return 0
        val today = LocalDate.now()
        val preorderListings = cached.listings.filter { it.releaseDate != null && !it.releaseDate.isBefore(today) }
        return preorderListings.map { keyOf(it) }.toSet().size
    }

    override suspend fun preorderItems(today: LocalDate): List<IncomingItem> {
        val all = incoming(today, includeUndated = false, includePriceless = true, forceRefresh = false)
        return all.filter { it.releaseDate != null && !it.releaseDate.isBefore(today) }
    }

    override suspend fun newsItems(today: LocalDate): List<IncomingItem> {
        val cached = localCacheStore?.load() ?: return emptyList()
        val twoMonthsAgo = today.minusMonths(2)

        val games = cached.games.associateBy { it.id }
        val sources = cached.sources.associateBy { it.id }
        val products = cached.products.associateBy { it.id }

        val userActions = runCatching { api.userActions() }.getOrDefault(emptyList())
        val actionsMap = latestActionsByKey(userActions)
        val remoteSeenKeys = actionsMap.filterValues { it == Action.SEEN }.keys
        val seenKeys = seenStore?.keys().orEmpty() + remoteSeenKeys

        val allItems = buildIncomingItems(
            listings = cached.listings,
            games = games,
            sources = sources,
            ignoredKeys = emptySet(),
            today = today,
            sourceIds = null,
            includeUndated = true,
            includePriceless = true,
            products = products,
            newlyAddedListingIds = cached.newlyAddedListingIds,
            seenKeys = seenKeys,
        )

        return allItems.filter { item ->
            val date = item.releaseDate
            val isPreorder = date != null && !date.isBefore(today)
            val isTooOld = date != null && date.isBefore(twoMonthsAgo)
            !isPreorder && !isTooOld
        }
    }

    override suspend fun findItem(key: String, today: LocalDate): IncomingItem? {
        val cached = localCacheStore?.load()
        val games = cached?.games?.associateBy { it.id } ?: emptyMap()
        val sources = cached?.sources?.associateBy { it.id } ?: emptyMap()
        val products = cached?.products?.associateBy { it.id } ?: emptyMap()

        val allItems = buildIncomingItems(
            listings = cached?.listings.orEmpty(),
            games = games,
            sources = sources,
            ignoredKeys = emptySet(),
            today = LocalDate.MIN,
            sourceIds = null,
            includeUndated = true,
            includePriceless = true,
            products = products,
        )

        val directMatch = allItems.firstOrNull { it.key == key }
        if (directMatch != null) return directMatch

        if (key.startsWith("bgg:")) {
            val bggId = key.removePrefix("bgg:").toIntOrNull()
            if (bggId != null) {
                val matchByBggId = allItems.firstOrNull { it.game?.bggId == bggId || it.bggSelectionKeys.contains(key) }
                if (matchByBggId != null) return matchByBggId

                val savedGame = cached?.games?.firstOrNull { it.bggId == bggId }
                val bggGame = bggCollectionStore?.load()?.games?.firstOrNull { it.bggId == bggId }
                val name = savedGame?.name ?: bggGame?.name ?: "Gioco BGG #$bggId"
                val imageUrl = savedGame?.imageUrl ?: bggGame?.imageUrl
                val gameToUse = savedGame ?: Game(
                    id = 0L,
                    name = name,
                    kind = bggGame?.kind ?: "base",
                    bggId = bggId,
                    bggRating = bggGame?.rating,
                    bggRank = null,
                    imageUrl = imageUrl,
                )
                val matchingProduct = savedGame?.let { g -> cached.products.firstOrNull { it.gameId == g.id } }
                return IncomingItem(
                    key = key,
                    title = matchingProduct?.title ?: name,
                    imageUrl = matchingProduct?.imageUrl ?: imageUrl,
                    releaseDate = matchingProduct?.releaseDate,
                    kind = if (matchingProduct?.releaseDate != null) com.meepleradar.data.model.ItemKind.UPCOMING else com.meepleradar.data.model.ItemKind.NEW_UNDATED,
                    game = gameToUse,
                    offers = emptyList(),
                    customDescription = matchingProduct?.description ?: savedGame?.description,
                )
            }
        }

        return incoming(today, includeUndated = true, includePriceless = true, forceRefresh = false).firstOrNull { it.key == key }
    }

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
        return buildItems(ignored, LocalDate.MIN, sourceIds, includeUndated, includePriceless, forceRefresh, onProgress).filter { it.key in favoriteKeys }
    }

    override suspend fun sources(): List<Source> {
        val cached = localCacheStore?.load()
        if (cached != null && cached.sources.isNotEmpty()) {
            return cached.sources
        }
        return api.sources()
    }

    override fun seenKeys(): Set<String> = seenStore?.keys().orEmpty()

    override fun markLocalSeen(key: String, seen: Boolean) {
        seenStore?.setSeen(key, seen)
    }

    override suspend fun setAction(key: String, action: Action) {
        val (kind, id) = parseKey(key)
        when (action) {
            Action.FAVORITE -> favoriteStore.setFavorite(key, true)
            Action.UNFAVORITED -> favoriteStore.setFavorite(key, false)
            Action.IGNORED -> favoriteStore.setFavorite(key, false)
            Action.SEEN -> seenStore?.setSeen(key, true)
        }

        runCatching {
            val row = when (kind) {
                "game" -> UserActionRow(gameId = id, listingId = null, action = action)
                "listing" -> UserActionRow(gameId = null, listingId = id, action = action)
                "product" -> UserActionRow(gameId = null, listingId = null, action = action, productId = id)
                "bgg" -> {
                    val savedGame = localCacheStore?.load()?.games?.firstOrNull { it.bggId == id.toInt() }
                    UserActionRow(gameId = savedGame?.id, listingId = null, action = action)
                }
                else -> throw IllegalArgumentException("chiave non valida: $key")
            }
            if (row.gameId != null || row.listingId != null || row.productId != null) {
                api.insertAction(row)
            }
        }
    }

    override suspend fun linkListingToBgg(
        listingId: Long,
        currentGameId: Long?,
        details: com.meepleradar.data.remote.BoardGameDetails,
        fallbackTitle: String,
    ): Pair<Long?, Long?> {
        val (gameId, productId) = api.linkListingToBgg(
            listingId = listingId,
            currentGameId = currentGameId,
            details = details,
            fallbackTitle = fallbackTitle,
        )
        if (gameId != null) {
            val title = details.primaryName ?: details.englishName ?: fallbackTitle
            val kind = if (details.expands.isNotEmpty()) "expansion" else "base"
            val existing = localCacheStore?.load()?.games?.firstOrNull { it.id == gameId }
            val linkedGame = Game(
                id = gameId,
                name = title,
                kind = kind,
                bggId = details.bggId,
                bggRating = details.bggRating,
                bggRank = details.bggRank,
                imageUrl = details.imageUrl,
                description = existing?.description ?: details.description,
            )
            val linkedProduct = productId?.let { pId ->
                Product(
                    id = pId,
                    title = title,
                    imageUrl = details.imageUrl,
                    releaseDate = null,
                    description = details.description,
                    url = "https://boardgamegeek.com/boardgame/${details.bggId}",
                    gameId = gameId,
                )
            }
            val cachedDb = localCacheStore?.load()
            val newProducts = if (linkedProduct != null && cachedDb != null) {
                cachedDb.products.filterNot { it.id == productId } + linkedProduct
            } else cachedDb?.products.orEmpty()

            localCacheStore?.upsertGame(linkedGame, listingId.takeIf { it > 0 }, productId)
            if (linkedProduct != null && cachedDb != null) {
                localCacheStore.saveIfChanged(
                    newListings = localCacheStore.load()?.listings.orEmpty(),
                    newGames = localCacheStore.load()?.games.orEmpty(),
                    newSources = cachedDb.sources,
                    newProducts = newProducts,
                )
            }
            if (listingId <= 0) refreshListingsAfterBggWrite()
        }
        return gameId to productId
    }

    private suspend fun refreshListingsAfterBggWrite() {
        val cached = localCacheStore?.load() ?: return
        val refreshedListings = api.listings()
        val refreshedIds = refreshedListings.mapTo(mutableSetOf()) { it.id }
        localCacheStore.saveIfChanged(
            newListings = cached.listings.filterNot { it.id in refreshedIds } + refreshedListings,
            newGames = cached.games,
            newSources = cached.sources,
            newProducts = cached.products,
        )
    }

    override suspend fun updateGameBggStats(
        bggId: Int,
        bggRating: Double?,
        bggRank: Int?,
        description: String?,
    ) {
        api.updateGameBggStats(bggId, bggRating, bggRank, description)
        localCacheStore?.load()?.let { cached ->
            cached.games.firstOrNull { it.bggId == bggId }?.let { game ->
                localCacheStore.upsertGame(
                    game.copy(
                        bggRating = bggRating ?: game.bggRating,
                        bggRank = bggRank ?: game.bggRank,
                        description = description?.takeIf(String::isNotBlank) ?: game.description,
                    ),
                )
            }
        }
    }

    override suspend fun previewGameSave(item: IncomingItem): GameSavePreview {
        val bggId = item.game?.bggId ?: 0
        val listings = api.findCandidateListingsToLink(item.title, bggId)
        return GameSavePreview(listings)
    }

    override suspend fun saveGameToDatabase(
        item: IncomingItem,
        selectedListingIdsToLink: List<Long>,
    ): Long? {
        val gameId = api.saveGameToDatabase(item, selectedListingIdsToLink)
        if (gameId != null && gameId > 0) {
            val existing = localCacheStore?.load()?.games?.firstOrNull { it.id == gameId }
            val linkedGame = Game(
                id = gameId,
                name = item.game?.name ?: item.title,
                kind = item.game?.kind ?: "base",
                bggId = item.game?.bggId,
                bggRating = item.game?.bggRating,
                bggRank = item.game?.bggRank,
                imageUrl = item.game?.imageUrl ?: item.imageUrl,
                description = existing?.description ?: item.game?.description,
            )
            localCacheStore?.upsertGame(linkedGame, item.key.removePrefix("listing:").toLongOrNull())
            refreshListingsAfterBggWrite()
        }
        return gameId
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
                _newlyAddedCount.value = updatedDb.newlyAddedCount
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

        val userActions = runCatching { api.userActions() }.getOrDefault(emptyList())
        val remoteSeenKeys = latestActionsByKey(userActions).filterValues { it == Action.SEEN }.keys
        val seenKeys = seenStore?.keys().orEmpty() + remoteSeenKeys

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
            newlyAddedListingIds = cached?.newlyAddedListingIds.orEmpty(),
            seenKeys = seenKeys,
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
