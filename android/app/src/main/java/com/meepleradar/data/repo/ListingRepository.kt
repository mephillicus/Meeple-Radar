package com.meepleradar.data.repo

import com.meepleradar.data.local.CachedDatabase
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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

    suspend fun favoriteKeys(today: LocalDate): Set<String> =
        favorites(today).map { it.key }.toSet()

    suspend fun newsItems(today: LocalDate): List<IncomingItem>

    suspend fun refreshNewsItems(today: LocalDate): List<IncomingItem> {
        incoming(today, forceRefresh = true)
        return newsItems(today)
    }

    suspend fun preorderItems(today: LocalDate): List<IncomingItem>

    suspend fun refreshPreorderItems(today: LocalDate): List<IncomingItem> {
        incoming(today, forceRefresh = true)
        return preorderItems(today)
    }

    suspend fun findItem(key: String, today: LocalDate): IncomingItem?

    suspend fun sources(): List<Source>

    suspend fun setAction(key: String, action: Action)

    suspend fun ignoredItems(today: LocalDate): List<IncomingItem> = emptyList()

    suspend fun restoreIgnored(key: String) {
        setAction(key, Action.UNFAVORITED)
    }

    fun seenKeys(): Set<String> = emptySet()

    fun markLocalSeen(key: String, seen: Boolean = true) {}

    fun markAllLocalSeen(keys: Collection<String>) {
        keys.forEach { markLocalSeen(it, true) }
    }

    fun lastDbModifiedTimestamp(): Long

    fun lastDbModifiedTimestampFlow(): StateFlow<Long> = MutableStateFlow(lastDbModifiedTimestamp())

    fun newlyAddedCount(): Int

    fun newlyAddedCountFlow(): StateFlow<Int>

    fun preorderCount(): Int

    fun preorderCountFlow(): StateFlow<Int> = MutableStateFlow(preorderCount())

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

    private val _newlyAddedCount = MutableStateFlow(localCacheStore?.newlyAddedCount() ?: 0)
    private val _preorderCount = MutableStateFlow(0)
    private val _lastDbModifiedTimestamp = MutableStateFlow(localCacheStore?.lastModifiedTimestamp() ?: 0L)

    private val actionsLock = Any()

    @Volatile
    private var cachedUserActions: List<UserActionRow>? = null

    @Volatile
    private var cachedUserActionsAtMs: Long = 0L

    override fun lastDbModifiedTimestamp(): Long {
        val current = _lastDbModifiedTimestamp.value
        if (current > 0L) return current
        return localCacheStore?.lastModifiedTimestamp() ?: 0L
    }

    override fun lastDbModifiedTimestampFlow(): StateFlow<Long> = _lastDbModifiedTimestamp.asStateFlow()

    override fun newlyAddedCount(): Int = _newlyAddedCount.value

    override fun newlyAddedCountFlow(): StateFlow<Int> = _newlyAddedCount.asStateFlow()

    override fun preorderCount(): Int {
        val current = _preorderCount.value
        if (current > 0) return current
        val cached = localCacheStore?.load() ?: return 0
        val count = computePreorderCount(cached, LocalDate.now())
        _preorderCount.value = count
        return count
    }

    override fun preorderCountFlow(): StateFlow<Int> = _preorderCount.asStateFlow()

    override suspend fun preorderItems(today: LocalDate): List<IncomingItem> =
        loadPreorderItems(today, forceRefresh = false)

    override suspend fun refreshPreorderItems(today: LocalDate): List<IncomingItem> =
        loadPreorderItems(today, forceRefresh = true)

    private suspend fun loadPreorderItems(today: LocalDate, forceRefresh: Boolean): List<IncomingItem> {
        val all = incoming(today, includeUndated = false, includePriceless = true, forceRefresh = forceRefresh)
        val preorders = all.filter { it.releaseDate != null && !it.releaseDate.isBefore(today) }
        _preorderCount.value = preorders.size
        return preorders
    }

    override suspend fun newsItems(today: LocalDate): List<IncomingItem> =
        loadNewsItems(today, forceRefresh = false)

    override suspend fun refreshNewsItems(today: LocalDate): List<IncomingItem> =
        loadNewsItems(today, forceRefresh = true)

    private suspend fun loadNewsItems(today: LocalDate, forceRefresh: Boolean): List<IncomingItem> {
        val (userActions, db) = coroutineScope {
            val actionsDeferred = async { getUserActions(forceRefresh = forceRefresh) }
            val dbDeferred = async { ensureDatabase(forceRefresh = forceRefresh) }
            actionsDeferred.await() to dbDeferred.await()
        }
        if (db.listings.isEmpty()) {
            _newlyAddedCount.value = 0
            return emptyList()
        }

        val actionsMap = latestActionsByKey(userActions)
        val ignoredKeys = actionsMap.filterValues { it == Action.IGNORED }.keys
        val remoteSeenKeys = userActions.filter { it.action == Action.SEEN }.mapNotNull { it.keyOrNull() }.toSet()
        val seenKeys = seenStore?.keys().orEmpty() + remoteSeenKeys
        val twoMonthsAgo = today.minusMonths(2)

        val news = withContext(Dispatchers.Default) {
            val games = db.games.associateBy { it.id }
            val sources = db.sources.associateBy { it.id }
            val products = db.products.associateBy { it.id }

            val allItems = buildIncomingItems(
                listings = db.listings,
                games = games,
                sources = sources,
                ignoredKeys = ignoredKeys,
                today = today,
                sourceIds = null,
                includeUndated = true,
                includePriceless = true,
                products = products,
                newlyAddedListingIds = db.newlyAddedListingIds,
                seenKeys = seenKeys,
            )

            allItems.filter { item ->
                val date = item.releaseDate
                val isPreorder = date != null && !date.isBefore(today)
                val isTooOld = date != null && date.isBefore(twoMonthsAgo)
                !isPreorder && !isTooOld
            }
        }

        _newlyAddedCount.value = news.count { it.isNewlyAdded }
        _preorderCount.value = computePreorderCount(db, today)
        _lastDbModifiedTimestamp.value = db.lastModifiedTimestamp
        return news
    }

    override suspend fun favoriteKeys(today: LocalDate): Set<String> {
        val userActions = getUserActions(forceRefresh = false)
        val actionsMap = latestActionsByKey(userActions)
        val ignored = actionsMap.filterValues { it == Action.IGNORED }.keys
        val remoteFavoriteKeys = actionsMap.filterValues { it == Action.FAVORITE }.keys
        return (favoriteStore.keys() + remoteFavoriteKeys) - ignored
    }

    override suspend fun findItem(key: String, today: LocalDate): IncomingItem? {
        val cached = withContext(Dispatchers.IO) { localCacheStore?.load() }
        val allItems = withContext(Dispatchers.Default) {
            val games = cached?.games?.associateBy { it.id } ?: emptyMap()
            val sources = cached?.sources?.associateBy { it.id } ?: emptyMap()
            val products = cached?.products?.associateBy { it.id } ?: emptyMap()

            buildIncomingItems(
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
        }

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
        val userActions = getUserActions(forceRefresh = forceRefresh)
        val actionsMap = latestActionsByKey(userActions)
        val ignored = actionsMap
            .filterValues { it == Action.IGNORED }
            .keys
            .toSet()
        val remoteSeenKeys = userActions.filter { it.action == Action.SEEN }.mapNotNull { it.keyOrNull() }.toSet()
        return buildItems(
            ignoredKeys = ignored,
            remoteSeenKeys = remoteSeenKeys,
            today = today,
            sourceIds = sourceIds,
            includeUndated = includeUndated,
            includePriceless = includePriceless,
            forceRefresh = forceRefresh,
            onProgress = onProgress,
        )
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
        val userActions = getUserActions(forceRefresh = forceRefresh)
        val actionsMap = latestActionsByKey(userActions)
        val ignored = actionsMap.filterValues { it == Action.IGNORED }.keys
        val remoteFavoriteKeys = actionsMap.filterValues { it == Action.FAVORITE }.keys
        val remoteSeenKeys = userActions.filter { it.action == Action.SEEN }.mapNotNull { it.keyOrNull() }.toSet()
        val favoriteKeys = favoriteStore.keys() + remoteFavoriteKeys
        return buildItems(
            ignoredKeys = ignored,
            remoteSeenKeys = remoteSeenKeys,
            today = LocalDate.MIN,
            sourceIds = sourceIds,
            includeUndated = includeUndated,
            includePriceless = includePriceless,
            forceRefresh = forceRefresh,
            onProgress = onProgress,
        ).filter { it.key in favoriteKeys }
    }

    override suspend fun sources(): List<Source> {
        val cached = withContext(Dispatchers.IO) { localCacheStore?.load() }
        if (cached != null && cached.sources.isNotEmpty()) {
            return cached.sources
        }
        return api.sources()
    }

    override fun seenKeys(): Set<String> = seenStore?.keys().orEmpty()

    override fun markLocalSeen(key: String, seen: Boolean) {
        seenStore?.setSeen(key, seen)
    }

    override fun markAllLocalSeen(keys: Collection<String>) {
        seenStore?.setAllSeen(keys)
    }

    override suspend fun setAction(key: String, action: Action) {
        val (kind, id) = parseKey(key)
        when (action) {
            Action.FAVORITE -> favoriteStore.setFavorite(key, true)
            Action.UNFAVORITED -> favoriteStore.setFavorite(key, false)
            Action.IGNORED -> favoriteStore.setFavorite(key, false)
            Action.SEEN -> seenStore?.setSeen(key, true)
        }

        val row = runCatching {
            when (kind) {
                "game" -> UserActionRow(gameId = id, listingId = null, action = action)
                "listing" -> UserActionRow(gameId = null, listingId = id, action = action)
                "product" -> UserActionRow(gameId = null, listingId = null, action = action, productId = id)
                "bgg" -> {
                    val savedGame = localCacheStore?.load()?.games?.firstOrNull { it.bggId == id.toInt() }
                    UserActionRow(gameId = savedGame?.id, listingId = null, action = action)
                }
                else -> throw IllegalArgumentException("chiave non valida: $key")
            }
        }.getOrNull()

        if (row != null && (row.gameId != null || row.listingId != null || row.productId != null)) {
            synchronized(actionsLock) {
                cachedUserActions = listOf(row) + cachedUserActions.orEmpty()
                if (cachedUserActionsAtMs == 0L) {
                    cachedUserActionsAtMs = System.currentTimeMillis()
                }
            }
            runCatching { api.insertAction(row) }
        }

        if (action == Action.IGNORED || action == Action.UNFAVORITED) {
            localCacheStore?.load()?.let { cached ->
                val actionsMap = latestActionsByKey(cachedUserActions.orEmpty())
                val ignoredKeys = actionsMap.filterValues { it == Action.IGNORED }.keys
                syncDerivedFlows(cached, ignoredKeys, LocalDate.now())
            }
        }
    }

    override suspend fun ignoredItems(today: LocalDate): List<IncomingItem> {
        val userActions = getUserActions(forceRefresh = false)
        val actionsMap = latestActionsByKey(userActions)
        val ignoredKeys = actionsMap.filterValues { it == Action.IGNORED }.keys
        if (ignoredKeys.isEmpty()) return emptyList()
        val db = ensureDatabase(forceRefresh = false)
        if (db.listings.isEmpty()) return emptyList()
        val remoteSeenKeys = userActions.filter { it.action == Action.SEEN }.mapNotNull { it.keyOrNull() }.toSet()
        val seenKeys = seenStore?.keys().orEmpty() + remoteSeenKeys
        return withContext(Dispatchers.Default) {
            val games = db.games.associateBy { it.id }
            val sources = db.sources.associateBy { it.id }
            val products = db.products.associateBy { it.id }
            val allItems = buildIncomingItems(
                listings = db.listings,
                games = games,
                sources = sources,
                ignoredKeys = emptySet(),
                today = today,
                sourceIds = null,
                includeUndated = true,
                includePriceless = true,
                products = products,
                newlyAddedListingIds = db.newlyAddedListingIds,
                seenKeys = seenKeys,
            )
            allItems.filter { item ->
                item.key in ignoredKeys || item.bggSelectionKeys.any { it in ignoredKeys }
            }
        }
    }

    override suspend fun restoreIgnored(key: String) {
        val currentlyIgnored = currentIgnoredKeys()
        if (key in currentlyIgnored) {
            setAction(key, Action.UNFAVORITED)
            return
        }
        val matchingItem = findItem(key, LocalDate.now())
        val matchingIgnoredKeys = matchingItem?.bggSelectionKeys?.filter { it in currentlyIgnored }.orEmpty()
        if (matchingIgnoredKeys.isNotEmpty()) {
            matchingIgnoredKeys.forEach { setAction(it, Action.UNFAVORITED) }
        } else {
            setAction(key, Action.UNFAVORITED)
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
                val (updated, _) = localCacheStore.saveIfChanged(
                    newListings = localCacheStore.load()?.listings.orEmpty(),
                    newGames = localCacheStore.load()?.games.orEmpty(),
                    newSources = cachedDb.sources,
                    newProducts = newProducts,
                )
                syncDerivedFlows(updated, currentIgnoredKeys(), LocalDate.now())
            } else {
                localCacheStore?.load()?.let { syncDerivedFlows(it, currentIgnoredKeys(), LocalDate.now()) }
            }
            if (listingId <= 0) refreshListingsAfterBggWrite()
        }
        return gameId to productId
    }

    private suspend fun refreshListingsAfterBggWrite() {
        val cached = localCacheStore?.load() ?: return
        val refreshedListings = api.listings()
        val refreshedIds = refreshedListings.mapTo(mutableSetOf()) { it.id }
        val (updated, _) = localCacheStore.saveIfChanged(
            newListings = cached.listings.filterNot { it.id in refreshedIds } + refreshedListings,
            newGames = cached.games,
            newSources = cached.sources,
            newProducts = cached.products,
        )
        syncDerivedFlows(updated, currentIgnoredKeys(), LocalDate.now())
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
                localCacheStore.load()?.let { syncDerivedFlows(it, currentIgnoredKeys(), LocalDate.now()) }
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

    private suspend fun getUserActions(forceRefresh: Boolean): List<UserActionRow> {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = cachedUserActions
            if (cached != null && now - cachedUserActionsAtMs < USER_ACTIONS_TTL_MS) {
                return cached
            }
        }
        val fetched = runCatching { api.userActions() }.getOrNull()
        return if (fetched != null) {
            synchronized(actionsLock) {
                cachedUserActions = fetched
                cachedUserActionsAtMs = System.currentTimeMillis()
            }
            fetched
        } else {
            cachedUserActions.orEmpty()
        }
    }

    private fun currentIgnoredKeys(): Set<String> =
        latestActionsByKey(cachedUserActions.orEmpty())
            .filterValues { it == Action.IGNORED }
            .keys

    private suspend fun ensureDatabase(
        forceRefresh: Boolean,
        onProgress: ((percent: Int, message: String) -> Unit)? = null,
    ): CachedDatabase {
        onProgress?.invoke(25, "Caricamento database locale…")
        var cached = withContext(Dispatchers.IO) { localCacheStore?.load() }

        if (forceRefresh || cached == null || cached.listings.isEmpty()) {
            onProgress?.invoke(40, "Connessione a Supabase…")
            val (remoteListings, remoteGames, remoteSources, remoteProducts) = coroutineScope {
                val listingsDeferred = async { runCatching { api.listings() }.getOrDefault(cached?.listings.orEmpty()) }
                val gamesDeferred = async { runCatching { api.games() }.getOrDefault(cached?.games.orEmpty()) }
                val sourcesDeferred = async { runCatching { api.sources() }.getOrDefault(cached?.sources.orEmpty()) }
                val productsDeferred = async { runCatching { api.products() }.getOrDefault(cached?.products.orEmpty()) }
                RemoteSnapshot(
                    listings = listingsDeferred.await(),
                    games = gamesDeferred.await(),
                    sources = sourcesDeferred.await(),
                    products = productsDeferred.await(),
                )
            }

            onProgress?.invoke(85, "Analisi modifiche e differenze…")
            cached = if (localCacheStore != null && remoteListings.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    localCacheStore.saveIfChanged(
                        newListings = remoteListings,
                        newGames = remoteGames,
                        newSources = remoteSources,
                        newProducts = remoteProducts,
                    ).first
                }
            } else {
                CachedDatabase(
                    listings = remoteListings,
                    games = remoteGames,
                    sources = remoteSources,
                    products = remoteProducts,
                    lastModifiedTimestamp = cached?.lastModifiedTimestamp ?: 0L,
                    newlyAddedCount = cached?.newlyAddedCount ?: 0,
                    newlyAddedListingIds = cached?.newlyAddedListingIds.orEmpty(),
                )
            }
        }

        return cached
    }

    private suspend fun buildItems(
        ignoredKeys: Set<String>,
        remoteSeenKeys: Set<String>,
        today: LocalDate,
        sourceIds: Set<String>?,
        includeUndated: Boolean,
        includePriceless: Boolean,
        forceRefresh: Boolean,
        onProgress: ((percent: Int, message: String) -> Unit)? = null,
    ): List<IncomingItem> {
        val db = ensureDatabase(forceRefresh = forceRefresh, onProgress = onProgress)

        onProgress?.invoke(95, "Elaborazione preferiti e filtri…")

        val seenKeys = seenStore?.keys().orEmpty() + remoteSeenKeys

        val result = withContext(Dispatchers.Default) {
            val games = db.games.associateBy { it.id }
            val sources = db.sources.associateBy { it.id }
            val products = db.products.associateBy { it.id }

            buildIncomingItems(
                listings = db.listings,
                games = games,
                sources = sources,
                ignoredKeys = ignoredKeys,
                today = today,
                sourceIds = sourceIds,
                includeUndated = includeUndated,
                includePriceless = includePriceless,
                products = products,
                newlyAddedListingIds = db.newlyAddedListingIds,
                seenKeys = seenKeys,
            )
        }

        val effectiveToday = if (today == LocalDate.MIN) LocalDate.now() else today
        syncDerivedFlows(db, ignoredKeys, effectiveToday)

        onProgress?.invoke(100, "Sincronizzazione completata!")
        return result
    }

    private fun syncDerivedFlows(db: CachedDatabase, ignoredKeys: Set<String>, today: LocalDate) {
        _lastDbModifiedTimestamp.value = db.lastModifiedTimestamp
        _preorderCount.value = computePreorderCount(db, today)
        _newlyAddedCount.value = computeNewlyAddedNewsCount(db, ignoredKeys, today)
    }

    private fun computePreorderCount(db: CachedDatabase, today: LocalDate): Int =
        db.listings
            .filter { !it.isAccessory() && it.releaseDate != null && !it.releaseDate.isBefore(today) }
            .map { keyOf(it) }
            .toSet()
            .size

    private fun computeNewlyAddedNewsCount(
        db: CachedDatabase,
        ignoredKeys: Set<String>,
        today: LocalDate,
    ): Int {
        if (db.listings.isEmpty()) return 0
        val twoMonthsAgo = today.minusMonths(2)
        val products = db.products.associateBy { it.id }
        return db.listings
            .filter { !it.isAccessory() }
            .groupBy { keyOf(it) }
            .count { (key, groupListings) ->
                val isIgnored = key in ignoredKeys || groupListings.any { listing ->
                    "listing:${listing.id}" in ignoredKeys ||
                        listing.gameId?.let { "game:$it" in ignoredKeys } == true ||
                        listing.productId?.let { "product:$it" in ignoredKeys } == true
                }
                if (isIgnored) return@count false

                val hasNewlyAdded = groupListings.any { listing ->
                    listing.id in db.newlyAddedListingIds || isWithin24Hours(listing.firstSeenAt, today)
                }
                if (!hasNewlyAdded) return@count false

                val product = groupListings.firstNotNullOfOrNull { l -> l.productId?.let { products[it] } }
                val futureDate = groupListings.mapNotNull { it.releaseDate }.filter { !it.isBefore(today) }.minOrNull()
                val releaseDate = futureDate
                    ?: groupListings.mapNotNull { it.releaseDate }.minOrNull()
                    ?: product?.releaseDate
                val isPreorder = releaseDate != null && !releaseDate.isBefore(today)
                val isTooOld = releaseDate != null && releaseDate.isBefore(twoMonthsAgo)
                !isPreorder && !isTooOld
            }
    }

    private data class RemoteSnapshot(
        val listings: List<Listing>,
        val games: List<Game>,
        val sources: List<Source>,
        val products: List<Product>,
    )

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
        actions
            .filter { it.action != Action.SEEN }
            .mapNotNull { row -> row.keyOrNull()?.let { it to row.action } }
            .distinctBy { (key, _) -> key }
            .toMap()

    private companion object {
        const val USER_ACTIONS_TTL_MS = 60_000L
    }
}
