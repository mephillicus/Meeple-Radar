package com.meepleradar.data.local

import android.content.Context
import com.meepleradar.data.local.db.CatalogDao
import com.meepleradar.data.local.db.GameEntity
import com.meepleradar.data.local.db.ListingEntity
import com.meepleradar.data.local.db.MeepleRadarDatabase
import com.meepleradar.data.local.db.ProductEntity
import com.meepleradar.data.local.db.SourceEntity
import com.meepleradar.data.local.db.SyncMetaEntity
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Product
import com.meepleradar.data.model.Source

/**
 * Implementazione SQLite/Room della cache locale del catalogo.
 *
 * Mantiene una copia leggera dei soli due contatori scalari in SharedPreferences
 * (`last_modified_timestamp` e `newly_added_count`) per consentire letture sincrone
 * non bloccanti all'avvio di [com.meepleradar.AppContainer] senza toccare SQLite sul main thread.
 * Alla prima lettura esegue automaticamente la migrazione one-shot dalla cache legacy
 * [SharedPreferencesLocalCacheStore], liberando lo spazio su disco al termine.
 */
class RoomLocalCacheStore(
    context: Context,
    private val dao: CatalogDao,
    private val legacyStore: SharedPreferencesLocalCacheStore? = null,
) : LocalCacheStore {

    constructor(
        context: Context,
        database: MeepleRadarDatabase,
        legacyStore: SharedPreferencesLocalCacheStore? = null,
    ) : this(context, database.catalogDao(), legacyStore)

    private val metaPrefs = context.getSharedPreferences(META_PREF_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile
    private var cachedInMemory: CachedDatabase? = null

    override fun lastModifiedTimestamp(): Long =
        cachedInMemory?.lastModifiedTimestamp ?: metaPrefs.getLong(KEY_TIMESTAMP, 0L)

    override fun newlyAddedCount(): Int =
        cachedInMemory?.newlyAddedCount ?: metaPrefs.getInt(KEY_NEWLY_ADDED_COUNT, 0)

    override fun load(): CachedDatabase? {
        cachedInMemory?.let { return it }
        return synchronized(lock) {
            cachedInMemory?.let { return@synchronized it }

            val listings = dao.getAllListings().map { it.toDomain() }
            val games = dao.getAllGames().map { it.toDomain() }
            val sources = dao.getAllSources().map { it.toDomain() }
            val products = dao.getAllProducts().map { it.toDomain() }
            val meta = dao.getSyncMeta()

            if (listings.isEmpty() && games.isEmpty() && meta == null) {
                val migrated = migrateFromLegacyIfPresent()
                if (migrated != null) {
                    cachedInMemory = migrated
                    return@synchronized migrated
                }
                return@synchronized null
            }

            val loaded = CachedDatabase(
                listings = listings,
                games = games,
                sources = sources,
                products = products,
                lastModifiedTimestamp = meta?.lastModifiedTimestamp ?: 0L,
                newlyAddedCount = meta?.newlyAddedCount ?: 0,
                newlyAddedListingIds = meta?.newlyAddedIds().orEmpty(),
                lastIncrementalSyncAt = meta?.lastIncrementalSyncAt,
                lastFullSyncAt = meta?.lastFullSyncAt ?: 0L,
            )
            persistMetaMirror(loaded.lastModifiedTimestamp, loaded.newlyAddedCount)
            cachedInMemory = loaded
            loaded
        }
    }

    override fun upsertGame(game: Game, linkedListingId: Long?, linkedProductId: Long?) {
        synchronized(lock) {
            val cached = load() ?: return
            val games = (cached.games.filterNot { it.id == game.id } + game).sortedBy { it.id }
            val listings = (if (linkedListingId == null) {
                cached.listings
            } else {
                cached.listings.map { listing ->
                    if (listing.id == linkedListingId) {
                        listing.copy(gameId = game.id, productId = linkedProductId)
                    } else {
                        listing
                    }
                }
            }).sortedBy { it.id }
            val timestamp = System.currentTimeMillis()
            val updated = cached.copy(
                listings = listings,
                games = games,
                lastModifiedTimestamp = timestamp,
            )
            dao.upsertGameAndOptionalListing(
                game = GameEntity.from(game),
                linkedListingId = linkedListingId,
                linkedProductId = linkedProductId,
                meta = updated.toSyncMeta(),
            )
            persistMetaMirror(timestamp, updated.newlyAddedCount)
            cachedInMemory = updated
        }
    }

    override fun saveIfChanged(
        newListings: List<Listing>,
        newGames: List<Game>,
        newSources: List<Source>,
        newProducts: List<Product>,
    ): Pair<CachedDatabase, Boolean> {
        val current = load()
        return saveFullSync(
            newListings = newListings,
            newGames = newGames,
            newSources = newSources,
            newProducts = newProducts,
            serverWatermarkIso = current?.lastIncrementalSyncAt,
            fullSyncTimestampMs = current?.lastFullSyncAt ?: 0L,
        )
    }

    override fun saveFullSync(
        newListings: List<Listing>,
        newGames: List<Game>,
        newSources: List<Source>,
        newProducts: List<Product>,
        serverWatermarkIso: String?,
        fullSyncTimestampMs: Long,
    ): Pair<CachedDatabase, Boolean> = synchronized(lock) {
        val current = load()

        val sortedNewListings = newListings.sortedBy { it.id }
        val sortedNewGames = newGames.sortedBy { it.id }
        val sortedNewSources = newSources.sortedBy { it.id }
        val sortedNewProducts = newProducts.sortedBy { it.id }

        val listingsChanged = current?.listings?.sortedBy { it.id } != sortedNewListings
        val gamesChanged = current?.games?.sortedBy { it.id } != sortedNewGames
        val sourcesChanged = current?.sources?.sortedBy { it.id } != sortedNewSources
        val productsChanged = current?.products?.sortedBy { it.id } != sortedNewProducts

        val hasDataChanges = current == null || listingsChanged || gamesChanged || sourcesChanged || productsChanged
        val timestamp = if (hasDataChanges) System.currentTimeMillis() else current.lastModifiedTimestamp

        val previousListingIds = current?.listings?.mapTo(mutableSetOf()) { it.id } ?: emptySet()
        val newListingIds = sortedNewListings.mapTo(mutableSetOf()) { it.id }
        val addedListings = if (current == null) emptyList() else sortedNewListings.filter { it.id !in previousListingIds }
        val newlyAddedIds = when {
            current == null -> emptySet()
            addedListings.isNotEmpty() -> addedListings.mapTo(mutableSetOf()) { it.id }
            else -> current.newlyAddedListingIds.filterTo(mutableSetOf()) { it in newListingIds }
        }
        val newlyAddedCount = newlyAddedIds.size
        val effectiveWatermark = serverWatermarkIso ?: current?.lastIncrementalSyncAt
        val effectiveFullSyncAt = if (fullSyncTimestampMs > 0L) fullSyncTimestampMs else (current?.lastFullSyncAt ?: 0L)

        val updatedDb = CachedDatabase(
            listings = sortedNewListings,
            games = sortedNewGames,
            sources = sortedNewSources,
            products = sortedNewProducts,
            lastModifiedTimestamp = timestamp,
            newlyAddedCount = newlyAddedCount,
            newlyAddedListingIds = newlyAddedIds,
            lastIncrementalSyncAt = effectiveWatermark,
            lastFullSyncAt = effectiveFullSyncAt,
        )

        if (hasDataChanges) {
            dao.replaceAllCatalog(
                listings = sortedNewListings.map { ListingEntity.from(it) },
                games = sortedNewGames.map { GameEntity.from(it) },
                sources = sortedNewSources.map { SourceEntity.from(it) },
                products = sortedNewProducts.map { ProductEntity.from(it) },
                meta = updatedDb.toSyncMeta(),
            )
        } else if (
            effectiveWatermark != current.lastIncrementalSyncAt ||
            effectiveFullSyncAt != current.lastFullSyncAt
        ) {
            dao.upsertSyncMeta(updatedDb.toSyncMeta())
        }

        persistMetaMirror(timestamp, newlyAddedCount)
        cachedInMemory = updatedDb
        updatedDb to hasDataChanges
    }

    override fun mergeDelta(
        deltaListings: List<Listing>,
        deltaGames: List<Game>,
        newSources: List<Source>,
        deltaProducts: List<Product>,
        serverWatermarkIso: String?,
    ): Pair<CachedDatabase, Boolean> = synchronized(lock) {
        val current = load() ?: CachedDatabase()

        val mergedListings = mergeById(current.listings, deltaListings) { it.id }
        val mergedGames = mergeById(current.games, deltaGames) { it.id }
        val mergedProducts = mergeById(current.products, deltaProducts) { it.id }
        val mergedSources = if (newSources.isNotEmpty()) newSources.sortedBy { it.id } else current.sources

        val listingsChanged = current.listings != mergedListings
        val gamesChanged = current.games != mergedGames
        val sourcesChanged = current.sources != mergedSources
        val productsChanged = current.products != mergedProducts
        val hasDataChanges = listingsChanged || gamesChanged || sourcesChanged || productsChanged

        val timestamp = if (hasDataChanges) System.currentTimeMillis() else current.lastModifiedTimestamp
        val previousListingIds = current.listings.mapTo(mutableSetOf()) { it.id }
        val addedListings = deltaListings.filter { it.id !in previousListingIds }
        val newlyAddedIds = if (addedListings.isNotEmpty()) {
            addedListings.mapTo(mutableSetOf()) { it.id }
        } else {
            current.newlyAddedListingIds
        }
        val newlyAddedCount = newlyAddedIds.size
        val effectiveWatermark = serverWatermarkIso ?: current.lastIncrementalSyncAt

        val updatedDb = CachedDatabase(
            listings = mergedListings,
            games = mergedGames,
            sources = mergedSources,
            products = mergedProducts,
            lastModifiedTimestamp = timestamp,
            newlyAddedCount = newlyAddedCount,
            newlyAddedListingIds = newlyAddedIds,
            lastIncrementalSyncAt = effectiveWatermark,
            lastFullSyncAt = current.lastFullSyncAt,
        )

        if (hasDataChanges) {
            dao.applyDelta(
                deltaListings = deltaListings.map { ListingEntity.from(it) },
                deltaGames = deltaGames.map { GameEntity.from(it) },
                sources = if (sourcesChanged) mergedSources.map { SourceEntity.from(it) } else emptyList(),
                deltaProducts = deltaProducts.map { ProductEntity.from(it) },
                meta = updatedDb.toSyncMeta(),
            )
        } else if (effectiveWatermark != current.lastIncrementalSyncAt) {
            dao.upsertSyncMeta(updatedDb.toSyncMeta())
        }

        persistMetaMirror(timestamp, newlyAddedCount)
        cachedInMemory = updatedDb
        updatedDb to hasDataChanges
    }

    private fun migrateFromLegacyIfPresent(): CachedDatabase? {
        val legacy = legacyStore ?: return null
        if (!legacy.hasLegacyData()) return null
        val loadedLegacy = legacy.load() ?: return null
        dao.replaceAllCatalog(
            listings = loadedLegacy.listings.sortedBy { it.id }.map { ListingEntity.from(it) },
            games = loadedLegacy.games.sortedBy { it.id }.map { GameEntity.from(it) },
            sources = loadedLegacy.sources.sortedBy { it.id }.map { SourceEntity.from(it) },
            products = loadedLegacy.products.sortedBy { it.id }.map { ProductEntity.from(it) },
            meta = loadedLegacy.toSyncMeta(),
        )
        persistMetaMirror(loadedLegacy.lastModifiedTimestamp, loadedLegacy.newlyAddedCount)
        legacy.clear()
        return loadedLegacy
    }

    private fun persistMetaMirror(timestamp: Long, newlyAddedCount: Int) {
        metaPrefs.edit()
            .putLong(KEY_TIMESTAMP, timestamp)
            .putInt(KEY_NEWLY_ADDED_COUNT, newlyAddedCount)
            .apply()
    }

    private fun CachedDatabase.toSyncMeta() = SyncMetaEntity(
        id = SyncMetaEntity.SINGLETON_ID,
        lastModifiedTimestamp = lastModifiedTimestamp,
        newlyAddedCount = newlyAddedCount,
        newlyAddedListingIdsCsv = newlyAddedListingIds.joinToString(","),
        lastIncrementalSyncAt = lastIncrementalSyncAt,
        lastFullSyncAt = lastFullSyncAt,
    )

    private companion object {
        const val META_PREF_NAME = "meeple_radar_db_meta"
        const val KEY_TIMESTAMP = "last_modified_timestamp"
        const val KEY_NEWLY_ADDED_COUNT = "newly_added_count"
    }
}
