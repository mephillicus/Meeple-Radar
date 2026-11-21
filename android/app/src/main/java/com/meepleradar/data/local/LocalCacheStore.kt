package com.meepleradar.data.local

import android.content.Context
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Product
import com.meepleradar.data.model.Source
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class CachedDatabase(
    val listings: List<Listing> = emptyList(),
    val games: List<Game> = emptyList(),
    val sources: List<Source> = emptyList(),
    val products: List<Product> = emptyList(),
    val lastModifiedTimestamp: Long = 0L,
    val newlyAddedCount: Int = 0,
    val newlyAddedListingIds: Set<Long> = emptySet(),
    val lastIncrementalSyncAt: String? = null,
    val lastFullSyncAt: Long = 0L,
)

interface LocalCacheStore {
    fun load(): CachedDatabase?

    fun lastModifiedTimestamp(): Long = load()?.lastModifiedTimestamp ?: 0L

    fun newlyAddedCount(): Int = load()?.newlyAddedCount ?: 0

    fun upsertGame(game: Game, linkedListingId: Long? = null, linkedProductId: Long? = null) {
        val cached = load() ?: return
        val games = cached.games.filterNot { it.id == game.id } + game
        val listings = if (linkedListingId == null) cached.listings else {
            cached.listings.map { listing ->
                if (listing.id == linkedListingId) listing.copy(
                    gameId = game.id,
                    productId = linkedProductId,
                ) else listing
            }
        }
        saveIfChanged(
            newListings = listings,
            newGames = games,
            newSources = cached.sources,
            newProducts = cached.products,
        )
    }

    fun saveIfChanged(
        newListings: List<Listing>,
        newGames: List<Game>,
        newSources: List<Source>,
        newProducts: List<Product>,
    ): Pair<CachedDatabase, Boolean>

    fun saveFullSync(
        newListings: List<Listing>,
        newGames: List<Game>,
        newSources: List<Source>,
        newProducts: List<Product>,
        serverWatermarkIso: String? = null,
        fullSyncTimestampMs: Long = System.currentTimeMillis(),
    ): Pair<CachedDatabase, Boolean> {
        val (saved, changed) = saveIfChanged(
            newListings = newListings,
            newGames = newGames,
            newSources = newSources,
            newProducts = newProducts,
        )
        return saved.copy(
            lastIncrementalSyncAt = serverWatermarkIso ?: saved.lastIncrementalSyncAt,
            lastFullSyncAt = fullSyncTimestampMs,
        ) to changed
    }

    fun mergeDelta(
        deltaListings: List<Listing>,
        deltaGames: List<Game>,
        newSources: List<Source>,
        deltaProducts: List<Product>,
        serverWatermarkIso: String? = null,
    ): Pair<CachedDatabase, Boolean> {
        val current = load() ?: CachedDatabase()
        val mergedListings = mergeById(current.listings, deltaListings) { it.id }
        val mergedGames = mergeById(current.games, deltaGames) { it.id }
        val mergedProducts = mergeById(current.products, deltaProducts) { it.id }
        val mergedSources = if (newSources.isNotEmpty()) newSources else current.sources
        val (saved, changed) = saveIfChanged(
            newListings = mergedListings,
            newGames = mergedGames,
            newSources = mergedSources,
            newProducts = mergedProducts,
        )
        return saved.copy(
            lastIncrementalSyncAt = serverWatermarkIso ?: current.lastIncrementalSyncAt,
            lastFullSyncAt = current.lastFullSyncAt,
        ) to changed
    }
}

internal inline fun <T> mergeById(
    existing: List<T>,
    delta: List<T>,
    crossinline idOf: (T) -> Long,
): List<T> {
    if (delta.isEmpty()) return existing
    val map = LinkedHashMap<Long, T>(existing.size + delta.size)
    existing.forEach { map[idOf(it)] = it }
    delta.forEach { map[idOf(it)] = it }
    return map.values.sortedBy { idOf(it) }
}

class SharedPreferencesLocalCacheStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LocalCacheStore {

    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile
    private var cachedInMemory: CachedDatabase? = null

    override fun lastModifiedTimestamp(): Long =
        cachedInMemory?.lastModifiedTimestamp ?: preferences.getLong(KEY_TIMESTAMP, 0L)

    override fun newlyAddedCount(): Int =
        cachedInMemory?.newlyAddedCount ?: preferences.getInt(KEY_NEWLY_ADDED_COUNT, 0)

    fun hasLegacyData(): Boolean = preferences.contains(KEY_LISTINGS)

    fun clear() {
        synchronized(lock) {
            cachedInMemory = null
            preferences.edit().clear().apply()
        }
    }

    override fun load(): CachedDatabase? {
        cachedInMemory?.let { return it }
        return synchronized(lock) {
            cachedInMemory?.let { return@synchronized it }
            val listingsJson = preferences.getString(KEY_LISTINGS, null) ?: return@synchronized null
            val gamesJson = preferences.getString(KEY_GAMES, null) ?: "[]"
            val sourcesJson = preferences.getString(KEY_SOURCES, null) ?: "[]"
            val productsJson = preferences.getString(KEY_PRODUCTS, null) ?: "[]"
            val timestamp = preferences.getLong(KEY_TIMESTAMP, 0L)
            val addedCount = preferences.getInt(KEY_NEWLY_ADDED_COUNT, 0)
            val addedIdsString = preferences.getString(KEY_NEWLY_ADDED_IDS, "") ?: ""
            val addedIds = if (addedIdsString.isBlank()) emptySet() else addedIdsString.split(',').mapNotNull { it.toLongOrNull() }.toSet()

            try {
                val cachedListings = json.decodeFromString<List<CachedListing>>(listingsJson).map { it.toDomain() }
                val cachedGames = json.decodeFromString<List<CachedGame>>(gamesJson).map { it.toDomain() }
                val cachedSources = json.decodeFromString<List<CachedSource>>(sourcesJson).map { it.toDomain() }
                val cachedProducts = json.decodeFromString<List<CachedProduct>>(productsJson).map { it.toDomain() }

                CachedDatabase(
                    listings = cachedListings,
                    games = cachedGames,
                    sources = cachedSources,
                    products = cachedProducts,
                    lastModifiedTimestamp = timestamp,
                    newlyAddedCount = addedCount,
                    newlyAddedListingIds = addedIds,
                ).also { cachedInMemory = it }
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun upsertGame(game: Game, linkedListingId: Long?, linkedProductId: Long?) {
        synchronized(lock) {
            val cached = load() ?: return
            val games = (cached.games.filterNot { it.id == game.id } + game).sortedBy { it.id }
            val listings = (if (linkedListingId == null) cached.listings else {
                cached.listings.map { listing ->
                    if (listing.id == linkedListingId) listing.copy(
                        gameId = game.id,
                        productId = linkedProductId,
                    ) else listing
                }
            }).sortedBy { it.id }
            val timestamp = System.currentTimeMillis()
            cachedInMemory = cached.copy(
                listings = listings,
                games = games,
                lastModifiedTimestamp = timestamp,
            )
            preferences.edit()
                .putString(KEY_GAMES, json.encodeToString(games.map { CachedGame.from(it) }))
                .putString(KEY_LISTINGS, json.encodeToString(listings.map { CachedListing.from(it) }))
                .putLong(KEY_TIMESTAMP, timestamp)
                .apply()
        }
    }

    override fun saveIfChanged(
        newListings: List<Listing>,
        newGames: List<Game>,
        newSources: List<Source>,
        newProducts: List<Product>,
    ): Pair<CachedDatabase, Boolean> = synchronized(lock) {
        val current = load()

        val sortedNewListings = newListings.sortedBy { it.id }
        val sortedNewGames = newGames.sortedBy { it.id }
        val sortedNewSources = newSources.sortedBy { it.id }
        val sortedNewProducts = newProducts.sortedBy { it.id }

        val sortedCurrentListings = current?.listings?.sortedBy { it.id }
        val sortedCurrentGames = current?.games?.sortedBy { it.id }
        val sortedCurrentSources = current?.sources?.sortedBy { it.id }
        val sortedCurrentProducts = current?.products?.sortedBy { it.id }

        val listingsChanged = sortedCurrentListings != sortedNewListings
        val gamesChanged = sortedCurrentGames != sortedNewGames
        val sourcesChanged = sortedCurrentSources != sortedNewSources
        val productsChanged = sortedCurrentProducts != sortedNewProducts

        val hasChanges = current == null || listingsChanged || gamesChanged || sourcesChanged || productsChanged

        val timestamp = if (hasChanges) System.currentTimeMillis() else (current?.lastModifiedTimestamp ?: System.currentTimeMillis())

        val previousListingIds = current?.listings?.mapTo(mutableSetOf()) { it.id } ?: emptySet()
        val newListingIds = sortedNewListings.mapTo(mutableSetOf()) { it.id }
        val addedListings = if (current == null) emptyList() else sortedNewListings.filter { it.id !in previousListingIds }
        val newlyAddedIds = when {
            current == null -> emptySet()
            addedListings.isNotEmpty() -> addedListings.mapTo(mutableSetOf()) { it.id }
            else -> current.newlyAddedListingIds.filterTo(mutableSetOf()) { it in newListingIds }
        }
        val newlyAddedCount = newlyAddedIds.size

        if (hasChanges) {
            val dtoListings = sortedNewListings.map { CachedListing.from(it) }
            val dtoGames = sortedNewGames.map { CachedGame.from(it) }
            val dtoSources = sortedNewSources.map { CachedSource.from(it) }
            val dtoProducts = sortedNewProducts.map { CachedProduct.from(it) }

            preferences.edit()
                .putString(KEY_LISTINGS, json.encodeToString(dtoListings))
                .putString(KEY_GAMES, json.encodeToString(dtoGames))
                .putString(KEY_SOURCES, json.encodeToString(dtoSources))
                .putString(KEY_PRODUCTS, json.encodeToString(dtoProducts))
                .putLong(KEY_TIMESTAMP, timestamp)
                .putInt(KEY_NEWLY_ADDED_COUNT, newlyAddedCount)
                .putString(KEY_NEWLY_ADDED_IDS, newlyAddedIds.joinToString(","))
                .apply()
        }

        val updatedDb = CachedDatabase(
            listings = sortedNewListings,
            games = sortedNewGames,
            sources = sortedNewSources,
            products = sortedNewProducts,
            lastModifiedTimestamp = timestamp,
            newlyAddedCount = newlyAddedCount,
            newlyAddedListingIds = newlyAddedIds,
        )
        cachedInMemory = updatedDb

        Pair(updatedDb, hasChanges)
    }

    private companion object {
        const val PREF_NAME = "meeple_radar_db_cache"
        const val KEY_LISTINGS = "cached_listings"
        const val KEY_GAMES = "cached_games"
        const val KEY_SOURCES = "cached_sources"
        const val KEY_PRODUCTS = "cached_products"
        const val KEY_TIMESTAMP = "last_modified_timestamp"
        const val KEY_NEWLY_ADDED_COUNT = "newly_added_count"
        const val KEY_NEWLY_ADDED_IDS = "newly_added_ids"
    }
}

@Serializable
private data class CachedListing(
    val id: Long,
    val sourceId: String,
    val title: String,
    val url: String,
    val price: Double?,
    val currency: String?,
    val availability: String?,
    val releaseDate: String?,
    val imageUrl: String?,
    val gameId: Long?,
    val matchConfidence: Double?,
    val productId: Long? = null,
    val description: String? = null,
    val firstSeenAt: String? = null,
    val updatedAt: String? = null,
) {
    fun toDomain() = Listing(
        id = id,
        sourceId = sourceId,
        title = title,
        url = url,
        price = price,
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
    )
    companion object {
        fun from(l: Listing) = CachedListing(
            id = l.id,
            sourceId = l.sourceId,
            title = l.title,
            url = l.url,
            price = l.price,
            currency = l.currency,
            availability = l.availability,
            releaseDate = l.releaseDate?.toString(),
            imageUrl = l.imageUrl,
            gameId = l.gameId,
            matchConfidence = l.matchConfidence,
            productId = l.productId,
            description = l.description,
            firstSeenAt = l.firstSeenAt,
            updatedAt = l.updatedAt,
        )
    }
}

@Serializable
private data class CachedGame(
    val id: Long,
    val name: String,
    val kind: String,
    val bggId: Int?,
    val bggRating: Double?,
    val bggRank: Int?,
    val imageUrl: String?,
    val description: String? = null,
    val updatedAt: String? = null,
) {
    fun toDomain() = Game(id, name, kind, bggId, bggRating, bggRank, imageUrl, description, updatedAt)
    companion object {
        fun from(g: Game) = CachedGame(g.id, g.name, g.kind, g.bggId, g.bggRating, g.bggRank, g.imageUrl, g.description, g.updatedAt)
    }
}

@Serializable
private data class CachedSource(val id: String, val name: String) {
    fun toDomain() = Source(id, name)
    companion object {
        fun from(s: Source) = CachedSource(s.id, s.name)
    }
}

@Serializable
private data class CachedProduct(
    val id: Long,
    val title: String?,
    val imageUrl: String?,
    val releaseDate: String?,
    val description: String? = null,
    val url: String? = null,
    val price: Double? = null,
    val currency: String? = null,
    val publisher: String? = null,
    val gameId: Long? = null,
    val updatedAt: String? = null,
) {
    fun toDomain() = Product(
        id = id,
        title = title,
        imageUrl = imageUrl,
        releaseDate = releaseDate.asLocalDateOrNull(),
        description = description,
        url = url,
        price = price,
        currency = currency,
        publisher = publisher,
        gameId = gameId,
        updatedAt = updatedAt,
    )
    companion object {
        fun from(p: Product) = CachedProduct(
            id = p.id,
            title = p.title,
            imageUrl = p.imageUrl,
            releaseDate = p.releaseDate?.toString(),
            description = p.description,
            url = p.url,
            price = p.price,
            currency = p.currency,
            publisher = p.publisher,
            gameId = p.gameId,
            updatedAt = p.updatedAt,
        )
    }
}

private fun String?.asLocalDateOrNull(): LocalDate? {
    if (isNullOrBlank()) return null
    return try {
        LocalDate.parse(this)
    } catch (e: DateTimeParseException) {
        null
    }
}
