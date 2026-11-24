package com.meepleradar.data.repo

import com.meepleradar.data.local.CachedDatabase
import com.meepleradar.data.local.FavoriteStore
import com.meepleradar.data.local.LocalCacheStore
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.GameSavePreview
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.ListingBestPrice
import com.meepleradar.data.model.PriceHistoryEntry
import com.meepleradar.data.model.Product
import com.meepleradar.data.model.Source
import com.meepleradar.data.model.UserAlert
import com.meepleradar.data.remote.SupabaseApi
import com.meepleradar.data.remote.UserActionRow
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
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

    suspend fun bestPricesForListings(listingIds: Collection<Long>): Map<Long, ListingBestPrice> = emptyMap()

    suspend fun priceHistoryForListings(listingIds: Collection<Long>): List<PriceHistoryEntry> = emptyList()

    suspend fun userAlerts(): List<UserAlert> = emptyList()

    suspend fun markAlertRead(alertId: Long) = Unit

    suspend fun markAllAlertsRead(alertIds: Collection<Long>) = Unit

    fun unreadAlertCountFlow(): StateFlow<Int> = MutableStateFlow(0)

    fun catalogRevisionFlow(): StateFlow<Long> = MutableStateFlow(0L)

    suspend fun localCacheStats(): LocalCacheStats = LocalCacheStats()

    suspend fun forceFullResync(today: LocalDate = LocalDate.now()): LocalCacheStats = localCacheStats()
}

data class LocalCacheStats(
    val listingsCount: Int = 0,
    val gamesCount: Int = 0,
    val sourcesCount: Int = 0,
    val lastModifiedTimestamp: Long = 0L,
    val lastFullSyncAt: Long = 0L,
)

class DefaultListingRepository(
    private val api: SupabaseApi,
    private val favoriteStore: FavoriteStore,
    private val localCacheStore: LocalCacheStore? = null,
    private val bggCollectionStore: com.meepleradar.data.local.BggCollectionStore? = null,
    private val seenStore: com.meepleradar.data.local.SeenStore? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : ListingRepository {

    private val _newlyAddedCount = MutableStateFlow(localCacheStore?.newlyAddedCount() ?: 0)
    private val _preorderCount = MutableStateFlow(0)
    private val _lastDbModifiedTimestamp = MutableStateFlow(localCacheStore?.lastModifiedTimestamp() ?: 0L)
    private val _unreadAlertCount = MutableStateFlow(0)
    private val _catalogRevision = MutableStateFlow(0L)

    private val actionsLock = Any()

    @Volatile
    private var cachedUserActions: List<UserActionRow>? = null

    @Volatile
    private var cachedUserActionsAtMs: Long = 0L

    @Volatile
    private var cachedPriceDrops: Map<Long, Double> = emptyMap()

    @Volatile
    private var cachedPriceDropsAtMs: Long = 0L

    @Volatile
    private var cachedUserAlerts: List<UserAlert> = emptyList()

    override fun unreadAlertCountFlow(): StateFlow<Int> = _unreadAlertCount.asStateFlow()

    override fun catalogRevisionFlow(): StateFlow<Long> = _catalogRevision.asStateFlow()

    private fun notifyCatalogUpdated() {
        _catalogRevision.value = _catalogRevision.value + 1L
    }

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
        val count = computePreorderCount(cached, currentIgnoredKeys(), LocalDate.now())
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
                priceDropsByListingId = cachedPriceDrops,
            )

            allItems.filter { item ->
                val date = item.releaseDate
                val isPreorder = date != null && !date.isBefore(today)
                val isTooOld = date != null && date.isBefore(twoMonthsAgo)
                !isPreorder && !isTooOld
            }
        }

        _newlyAddedCount.value = news.count { it.isNewlyAdded }
        _preorderCount.value = computePreorderCount(db, ignoredKeys, today)
        _lastDbModifiedTimestamp.value = db.lastModifiedTimestamp
        if (forceRefresh) {
            notifyCatalogUpdated()
        }
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
        val matchingAlert = cachedUserAlerts.firstOrNull { key in it.candidateItemKeys() }
        val keysToTry = buildList {
            add(key)
            matchingAlert?.candidateItemKeys()?.forEach { add(it) }
        }.distinct()

        val cached = withContext(Dispatchers.IO) { localCacheStore?.load() }
        if (cached != null) {
            resolveItemFromDatabase(cached, keysToTry, matchingAlert)?.let { return it }
        }

        val loaded = incoming(today, includeUndated = true, includePriceless = true, forceRefresh = false)
        keysToTry.firstNotNullOfOrNull { candidateKey ->
            loaded.firstOrNull { it.key == candidateKey }
                ?: loaded.firstOrNull { candidateKey in it.bggSelectionKeys }
        }?.let { return it }

        val refreshedCached = withContext(Dispatchers.IO) { localCacheStore?.load() }
        if (refreshedCached != null) {
            resolveItemFromDatabase(refreshedCached, keysToTry, matchingAlert)?.let { return it }
        }

        return matchingAlert?.let { alert -> buildFallbackAlertItem(alert, key) }
    }

    private suspend fun resolveItemFromDatabase(
        db: CachedDatabase,
        keysToTry: List<String>,
        matchingAlert: UserAlert?,
    ): IncomingItem? {
        val allItems = withContext(Dispatchers.Default) {
            val games = db.games.associateBy { it.id }
            val sources = db.sources.associateBy { it.id }
            val products = db.products.associateBy { it.id }

            buildIncomingItems(
                listings = db.listings,
                games = games,
                sources = sources,
                ignoredKeys = emptySet(),
                today = LocalDate.MIN,
                sourceIds = null,
                includeUndated = true,
                includePriceless = true,
                products = products,
                priceDropsByListingId = cachedPriceDrops,
            )
        }

        for (candidateKey in keysToTry) {
            val directMatch = allItems.firstOrNull { it.key == candidateKey }
                ?: allItems.firstOrNull { candidateKey in it.bggSelectionKeys }
            if (directMatch != null) return directMatch

            if (candidateKey.startsWith("game:")) {
                val gameId = candidateKey.removePrefix("game:").toLongOrNull()
                if (gameId != null) {
                    val matchByGame = allItems.firstOrNull { it.game?.id == gameId }
                    if (matchByGame != null) return matchByGame

                    val savedGame = db.games.firstOrNull { it.id == gameId }
                    if (savedGame != null) {
                        val matchByBgg = savedGame.bggId?.takeIf { it > 0 }?.let { bggId ->
                            allItems.firstOrNull { it.game?.bggId == bggId || "bgg:$bggId" in it.bggSelectionKeys }
                        }
                        if (matchByBgg != null) return matchByBgg

                        val matchingProduct = db.products.firstOrNull { it.gameId == savedGame.id }
                        val selectionKeys = listOfNotNull(
                            candidateKey,
                            matchingProduct?.id?.let { "product:$it" },
                            savedGame.bggId?.takeIf { it > 0 }?.let { "bgg:$it" },
                        ).distinct()
                        val title = matchingProduct?.title?.takeIf(String::isNotBlank) ?: savedGame.name
                        return IncomingItem(
                            key = matchingProduct?.id?.let { "product:$it" } ?: candidateKey,
                            title = title,
                            imageUrl = savedGame.imageUrl ?: matchingProduct?.imageUrl,
                            releaseDate = matchingProduct?.releaseDate,
                            kind = if (matchingProduct?.releaseDate != null) com.meepleradar.data.model.ItemKind.UPCOMING else com.meepleradar.data.model.ItemKind.NEW_UNDATED,
                            game = savedGame,
                            offers = emptyList(),
                            customDescription = matchingProduct?.description?.takeIf(String::isNotBlank) ?: savedGame.description,
                            lookupTitle = title,
                            bggSelectionKeys = selectionKeys,
                            reprintDate = matchingProduct?.reprintDate,
                        )
                    }
                }
            }

            if (candidateKey.startsWith("product:")) {
                val productId = candidateKey.removePrefix("product:").toLongOrNull()
                if (productId != null) {
                    val savedProduct = db.products.firstOrNull { it.id == productId }
                    if (savedProduct != null) {
                        val savedGame = savedProduct.gameId?.let { gId -> db.games.firstOrNull { it.id == gId } }
                        val matchByGame = savedGame?.let { g ->
                            allItems.firstOrNull {
                                it.game?.id == g.id ||
                                    (g.bggId != null && g.bggId > 0 && (it.game?.bggId == g.bggId || "bgg:${g.bggId}" in it.bggSelectionKeys))
                            }
                        }
                        if (matchByGame != null) return matchByGame

                        val title = savedProduct.title?.takeIf(String::isNotBlank) ?: savedGame?.name ?: "Prodotto #$productId"
                        val selectionKeys = listOfNotNull(
                            candidateKey,
                            savedGame?.id?.takeIf { it > 0 }?.let { "game:$it" },
                            savedGame?.bggId?.takeIf { it > 0 }?.let { "bgg:$it" },
                        ).distinct()
                        return IncomingItem(
                            key = candidateKey,
                            title = title,
                            imageUrl = savedGame?.imageUrl ?: savedProduct.imageUrl,
                            releaseDate = savedProduct.releaseDate,
                            kind = if (savedProduct.releaseDate != null) com.meepleradar.data.model.ItemKind.UPCOMING else com.meepleradar.data.model.ItemKind.NEW_UNDATED,
                            game = savedGame,
                            offers = emptyList(),
                            customDescription = savedProduct.description?.takeIf(String::isNotBlank) ?: savedGame?.description,
                            lookupTitle = title,
                            bggSelectionKeys = selectionKeys,
                            reprintDate = savedProduct.reprintDate,
                        )
                    }
                }
            }

            if (candidateKey.startsWith("bgg:")) {
                val bggId = candidateKey.removePrefix("bgg:").toIntOrNull()
                if (bggId != null) {
                    val matchByBggId = allItems.firstOrNull { it.game?.bggId == bggId || it.bggSelectionKeys.contains(candidateKey) }
                    if (matchByBggId != null) return matchByBggId

                    val savedGame = db.games.firstOrNull { it.bggId == bggId }
                    val bggGame = bggCollectionStore?.load()?.games?.firstOrNull { it.bggId == bggId }
                    val name = savedGame?.name ?: bggGame?.name ?: matchingAlert?.title ?: "Gioco BGG #$bggId"
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
                    val matchingProduct = savedGame?.let { g -> db.products.firstOrNull { it.gameId == g.id } }
                    return IncomingItem(
                        key = candidateKey,
                        title = matchingProduct?.title ?: name,
                        imageUrl = matchingProduct?.imageUrl ?: imageUrl,
                        releaseDate = matchingProduct?.releaseDate,
                        kind = if (matchingProduct?.releaseDate != null) com.meepleradar.data.model.ItemKind.UPCOMING else com.meepleradar.data.model.ItemKind.NEW_UNDATED,
                        game = gameToUse,
                        offers = emptyList(),
                        customDescription = matchingProduct?.description ?: savedGame?.description,
                        reprintDate = matchingProduct?.reprintDate,
                    )
                }
            }
        }

        if (matchingAlert != null) {
            val alertTitleNorm = normalizeLookupTitle(matchingAlert.title)
            if (alertTitleNorm.isNotEmpty()) {
                val byTitle = allItems.firstOrNull {
                    normalizeLookupTitle(it.title) == alertTitleNorm ||
                        normalizeLookupTitle(it.game?.name.orEmpty()) == alertTitleNorm
                }
                if (byTitle != null) return byTitle
            }
        }

        return null
    }

    private fun buildFallbackAlertItem(alert: UserAlert, fallbackKey: String): IncomingItem {
        val kind = if (alert.alertType == com.meepleradar.data.model.UserAlertType.NEW_EXPANSION) "expansion" else "base"
        val game = Game(
            id = alert.gameId ?: 0L,
            name = alert.title,
            kind = kind,
            bggId = alert.bggId,
            bggRating = null,
            bggRank = null,
            imageUrl = null,
        )
        return IncomingItem(
            key = fallbackKey,
            title = alert.title,
            imageUrl = null,
            releaseDate = null,
            kind = com.meepleradar.data.model.ItemKind.NEW_UNDATED,
            game = game,
            offers = emptyList(),
            lookupTitle = alert.title,
            bggSelectionKeys = (listOf(fallbackKey) + alert.candidateItemKeys()).distinct(),
        )
    }

    private fun normalizeLookupTitle(value: String): String =
        value.lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")

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
                    val savedGame = withContext(Dispatchers.IO) {
                        localCacheStore?.load()?.games?.firstOrNull { it.bggId == id.toInt() }
                    }
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
            val cached = withContext(Dispatchers.IO) { localCacheStore?.load() }
            if (cached != null) {
                val actionsMap = latestActionsByKey(cachedUserActions.orEmpty())
                val ignoredKeys = actionsMap.filterValues { it == Action.IGNORED }.keys
                syncDerivedFlows(cached, ignoredKeys, LocalDate.now())
            }
        }
        if (action != Action.SEEN) {
            notifyCatalogUpdated()
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
                priceDropsByListingId = cachedPriceDrops,
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
            val updatedDb = withContext(Dispatchers.IO) {
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
                    ).first
                } else {
                    localCacheStore?.load()
                }
            }
            if (updatedDb != null) {
                syncDerivedFlows(updatedDb, currentIgnoredKeys(), LocalDate.now())
            }
            if (listingId <= 0) refreshListingsAfterBggWrite()
            notifyCatalogUpdated()
        }
        return gameId to productId
    }

    private suspend fun refreshListingsAfterBggWrite() {
        val store = localCacheStore ?: return
        val cached = withContext(Dispatchers.IO) { store.load() } ?: return
        val refreshedListings = api.listings()
        val refreshedIds = refreshedListings.mapTo(mutableSetOf()) { it.id }
        val (updated, _) = withContext(Dispatchers.IO) {
            store.saveIfChanged(
                newListings = cached.listings.filterNot { it.id in refreshedIds } + refreshedListings,
                newGames = cached.games,
                newSources = cached.sources,
                newProducts = cached.products,
            )
        }
        syncDerivedFlows(updated, currentIgnoredKeys(), LocalDate.now())
    }

    override suspend fun updateGameBggStats(
        bggId: Int,
        bggRating: Double?,
        bggRank: Int?,
        description: String?,
    ) {
        api.updateGameBggStats(bggId, bggRating, bggRank, description)
        val updatedDb = withContext(Dispatchers.IO) {
            val cached = localCacheStore?.load() ?: return@withContext null
            val game = cached.games.firstOrNull { it.bggId == bggId } ?: return@withContext null
            localCacheStore.upsertGame(
                game.copy(
                    bggRating = bggRating ?: game.bggRating,
                    bggRank = bggRank ?: game.bggRank,
                    description = description?.takeIf(String::isNotBlank) ?: game.description,
                ),
            )
            localCacheStore.load()
        }
        if (updatedDb != null) {
            syncDerivedFlows(updatedDb, currentIgnoredKeys(), LocalDate.now())
            notifyCatalogUpdated()
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
            val listingId = item.key.removePrefix("listing:").toLongOrNull()
                ?: item.bggSelectionKeys.firstNotNullOfOrNull { it.removePrefix("listing:").toLongOrNull() }
            withContext(Dispatchers.IO) {
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
                localCacheStore?.upsertGame(linkedGame, listingId)
            }
            refreshListingsAfterBggWrite()
            notifyCatalogUpdated()
        }
        return gameId
    }

    override suspend fun bestPricesForListings(listingIds: Collection<Long>): Map<Long, ListingBestPrice> {
        val ids = listingIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val fetched = runCatching { api.listingBestPrices(ids) }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) {
            val drops = cachedPriceDrops.toMutableMap()
            fetched.forEach { entry ->
                val pct = entry.priceDropPct
                if (pct != null && pct > 0.0) {
                    drops[entry.listingId] = pct
                }
            }
            cachedPriceDrops = drops
        }
        return fetched.associateBy { it.listingId }
    }

    override suspend fun priceHistoryForListings(listingIds: Collection<Long>): List<PriceHistoryEntry> {
        val ids = listingIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return emptyList()
        return runCatching { api.listingPriceHistory(ids) }.getOrDefault(emptyList())
    }

    override suspend fun userAlerts(): List<UserAlert> {
        val alerts = runCatching { api.userAlerts() }.getOrDefault(emptyList())
        cachedUserAlerts = alerts
        _unreadAlertCount.value = alerts.count { it.isUnread }
        return alerts
    }

    override suspend fun markAlertRead(alertId: Long) {
        val nowIso = Instant.ofEpochMilli(clockMs()).toString()
        runCatching { api.markAlertRead(alertId, nowIso) }
        _unreadAlertCount.value = (_unreadAlertCount.value - 1).coerceAtLeast(0)
    }

    override suspend fun markAllAlertsRead(alertIds: Collection<Long>) {
        if (alertIds.isEmpty()) return
        val nowIso = Instant.ofEpochMilli(clockMs()).toString()
        runCatching { api.markAllAlertsRead(alertIds, nowIso) }
        _unreadAlertCount.value = 0
    }

    override suspend fun localCacheStats(): LocalCacheStats {
        val cached = withContext(Dispatchers.IO) { localCacheStore?.load() }
        return if (cached != null) {
            LocalCacheStats(
                listingsCount = cached.listings.size,
                gamesCount = cached.games.size,
                sourcesCount = cached.sources.size,
                lastModifiedTimestamp = cached.lastModifiedTimestamp,
                lastFullSyncAt = cached.lastFullSyncAt,
            )
        } else {
            LocalCacheStats(
                lastModifiedTimestamp = lastDbModifiedTimestamp(),
            )
        }
    }

    override suspend fun forceFullResync(today: LocalDate): LocalCacheStats {
        cachedUserActionsAtMs = 0L
        cachedPriceDropsAtMs = 0L
        val userActions = getUserActions(forceRefresh = true)
        val actionsMap = latestActionsByKey(userActions)
        val ignoredKeys = actionsMap.filterValues { it == Action.IGNORED }.keys
        val db = ensureDatabase(forceRefresh = true, forceFullSync = true)
        syncDerivedFlows(db, ignoredKeys, today)
        notifyCatalogUpdated()
        return LocalCacheStats(
            listingsCount = db.listings.size,
            gamesCount = db.games.size,
            sourcesCount = db.sources.size,
            lastModifiedTimestamp = db.lastModifiedTimestamp,
            lastFullSyncAt = db.lastFullSyncAt,
        )
    }

    private suspend fun refreshPriceDropsBestEffort(force: Boolean) {
        val now = clockMs()
        if (!force && cachedPriceDrops.isNotEmpty() && now - cachedPriceDropsAtMs < PRICE_DROPS_TTL_MS) {
            return
        }
        val fetched = runCatching { api.listingBestPrices(null) }.getOrNull() ?: return
        cachedPriceDrops = fetched
            .mapNotNull { entry ->
                val pct = entry.priceDropPct
                if (pct != null && pct > 0.0) entry.listingId to pct else null
            }
            .toMap()
        cachedPriceDropsAtMs = now
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
        forceFullSync: Boolean = false,
        onProgress: ((percent: Int, message: String) -> Unit)? = null,
    ): CachedDatabase {
        onProgress?.invoke(25, "Caricamento database locale…")
        var cached = withContext(Dispatchers.IO) { localCacheStore?.load() }

        val nowMs = clockMs()
        val incrementalSince = cached?.lastIncrementalSyncAt?.takeIf(String::isNotBlank)
        val canUseIncremental = forceRefresh &&
            !forceFullSync &&
            localCacheStore != null &&
            cached != null &&
            cached.listings.isNotEmpty() &&
            incrementalSince != null &&
            cached.lastFullSyncAt > 0L &&
            (nowMs - cached.lastFullSyncAt) < FULL_SYNC_INTERVAL_MS

        if (canUseIncremental) {
            onProgress?.invoke(40, "Sincronizzazione incrementale…")
            when (val outcome = fetchIncrementalDelta(incrementalSince)) {
                is DeltaOutcome.Success -> {
                    onProgress?.invoke(85, "Applicazione aggiornamenti…")
                    refreshPriceDropsBestEffort(force = true)
                    val nextWatermark = computeServerWatermark(
                        listings = outcome.snapshot.listings,
                        games = outcome.snapshot.games,
                        products = outcome.snapshot.products,
                        fallbackIso = incrementalSince,
                    )
                    return withContext(Dispatchers.IO) {
                        localCacheStore.mergeDelta(
                            deltaListings = outcome.snapshot.listings,
                            deltaGames = outcome.snapshot.games,
                            newSources = outcome.snapshot.sources,
                            deltaProducts = outcome.snapshot.products,
                            serverWatermarkIso = nextWatermark,
                        ).first
                    }
                }
                DeltaOutcome.NetworkError -> {
                    // Offline o errore transitorio: mantiene la cache locale integra.
                    return cached
                }
                DeltaOutcome.SchemaUnsupported -> {
                    // Fallback trasparente al full sync se la colonna updated_at non è presente.
                }
            }
        }

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
            refreshPriceDropsBestEffort(force = forceRefresh)
            val watermark = computeServerWatermark(
                listings = remoteListings,
                games = remoteGames,
                products = remoteProducts,
                fallbackIso = cached?.lastIncrementalSyncAt,
            ) ?: Instant.ofEpochMilli(nowMs).toString()

            cached = if (localCacheStore != null && remoteListings.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    localCacheStore.saveFullSync(
                        newListings = remoteListings,
                        newGames = remoteGames,
                        newSources = remoteSources,
                        newProducts = remoteProducts,
                        serverWatermarkIso = watermark,
                        fullSyncTimestampMs = nowMs,
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
                    lastIncrementalSyncAt = cached?.lastIncrementalSyncAt,
                    lastFullSyncAt = cached?.lastFullSyncAt ?: 0L,
                )
            }
        }

        return cached
    }

    private suspend fun fetchIncrementalDelta(sinceIso: String): DeltaOutcome = try {
        val snapshot = coroutineScope {
            val listingsDeferred = async { api.listingsUpdatedSince(sinceIso) }
            val gamesDeferred = async { api.gamesUpdatedSince(sinceIso) }
            val sourcesDeferred = async { runCatching { api.sources() }.getOrDefault(emptyList()) }
            val productsDeferred = async { api.productsUpdatedSince(sinceIso) }
            RemoteSnapshot(
                listings = listingsDeferred.await(),
                games = gamesDeferred.await(),
                sources = sourcesDeferred.await(),
                products = productsDeferred.await(),
            )
        }
        DeltaOutcome.Success(snapshot)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val msg = e.message.orEmpty()
        if (msg.contains("HTTP 400") || msg.contains("updated_at", ignoreCase = true) || msg.contains("42703")) {
            DeltaOutcome.SchemaUnsupported
        } else {
            DeltaOutcome.NetworkError
        }
    }

    private fun computeServerWatermark(
        listings: List<Listing>,
        games: List<Game>,
        products: List<Product>,
        fallbackIso: String?,
    ): String? {
        var maxInstant = fallbackIso.parseServerInstantOrNull()
        listings.forEach { l ->
            val inst = l.updatedAt.parseServerInstantOrNull()
            if (inst != null && (maxInstant == null || inst.isAfter(maxInstant))) {
                maxInstant = inst
            }
        }
        games.forEach { g ->
            val inst = g.updatedAt.parseServerInstantOrNull()
            if (inst != null && (maxInstant == null || inst.isAfter(maxInstant))) {
                maxInstant = inst
            }
        }
        products.forEach { p ->
            val inst = p.updatedAt.parseServerInstantOrNull()
            if (inst != null && (maxInstant == null || inst.isAfter(maxInstant))) {
                maxInstant = inst
            }
        }
        return maxInstant?.toString()
    }

    private fun String?.parseServerInstantOrNull(): Instant? {
        if (isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(this).toInstant() }
            .recoverCatching { Instant.parse(this) }
            .getOrNull()
    }

    private sealed interface DeltaOutcome {
        data class Success(val snapshot: RemoteSnapshot) : DeltaOutcome
        data object SchemaUnsupported : DeltaOutcome
        data object NetworkError : DeltaOutcome
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
                priceDropsByListingId = cachedPriceDrops,
            )
        }

        val effectiveToday = if (today == LocalDate.MIN) LocalDate.now() else today
        syncDerivedFlows(db, ignoredKeys, effectiveToday)
        if (forceRefresh) {
            notifyCatalogUpdated()
        }

        onProgress?.invoke(100, "Sincronizzazione completata!")
        return result
    }

    private fun syncDerivedFlows(db: CachedDatabase, ignoredKeys: Set<String>, today: LocalDate) {
        _lastDbModifiedTimestamp.value = db.lastModifiedTimestamp
        _preorderCount.value = computePreorderCount(db, ignoredKeys, today)
        _newlyAddedCount.value = computeNewlyAddedNewsCount(db, ignoredKeys, today)
    }

    private fun computePreorderCount(
        db: CachedDatabase,
        ignoredKeys: Set<String>,
        today: LocalDate,
    ): Int {
        if (db.listings.isEmpty()) return 0
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

                val futureDate = groupListings.mapNotNull { it.releaseDate }.filter { !it.isBefore(today) }.minOrNull()
                val releaseDate = futureDate
                    ?: groupListings.mapNotNull { it.releaseDate }.minOrNull()
                    ?: groupListings.firstNotNullOfOrNull { l -> l.productId?.let { products[it]?.releaseDate } }
                releaseDate != null && !releaseDate.isBefore(today)
            }
    }

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
        const val PRICE_DROPS_TTL_MS = 10L * 60_000L
        const val FULL_SYNC_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
