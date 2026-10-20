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
)

interface LocalCacheStore {
    fun load(): CachedDatabase?
    fun saveIfChanged(
        newListings: List<Listing>,
        newGames: List<Game>,
        newSources: List<Source>,
        newProducts: List<Product>,
    ): Pair<CachedDatabase, Boolean>
}

class SharedPreferencesLocalCacheStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LocalCacheStore {

    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    override fun load(): CachedDatabase? {
        val listingsJson = preferences.getString(KEY_LISTINGS, null) ?: return null
        val gamesJson = preferences.getString(KEY_GAMES, null) ?: "[]"
        val sourcesJson = preferences.getString(KEY_SOURCES, null) ?: "[]"
        val productsJson = preferences.getString(KEY_PRODUCTS, null) ?: "[]"
        val timestamp = preferences.getLong(KEY_TIMESTAMP, 0L)

        return try {
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
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun saveIfChanged(
        newListings: List<Listing>,
        newGames: List<Game>,
        newSources: List<Source>,
        newProducts: List<Product>,
    ): Pair<CachedDatabase, Boolean> {
        val current = load()

        val listingsChanged = current?.listings != newListings
        val gamesChanged = current?.games != newGames
        val sourcesChanged = current?.sources != newSources
        val productsChanged = current?.products != newProducts

        val hasChanges = current == null || listingsChanged || gamesChanged || sourcesChanged || productsChanged

        val timestamp = if (hasChanges) System.currentTimeMillis() else (current?.lastModifiedTimestamp ?: System.currentTimeMillis())

        if (hasChanges) {
            val dtoListings = newListings.map { CachedListing.from(it) }
            val dtoGames = newGames.map { CachedGame.from(it) }
            val dtoSources = newSources.map { CachedSource.from(it) }
            val dtoProducts = newProducts.map { CachedProduct.from(it) }

            preferences.edit()
                .putString(KEY_LISTINGS, json.encodeToString(dtoListings))
                .putString(KEY_GAMES, json.encodeToString(dtoGames))
                .putString(KEY_SOURCES, json.encodeToString(dtoSources))
                .putString(KEY_PRODUCTS, json.encodeToString(dtoProducts))
                .putLong(KEY_TIMESTAMP, timestamp)
                .apply()
        }

        val updatedDb = CachedDatabase(
            listings = newListings,
            games = newGames,
            sources = newSources,
            products = newProducts,
            lastModifiedTimestamp = timestamp,
        )

        return Pair(updatedDb, hasChanges)
    }

    private companion object {
        const val PREF_NAME = "meeple_radar_db_cache"
        const val KEY_LISTINGS = "cached_listings"
        const val KEY_GAMES = "cached_games"
        const val KEY_SOURCES = "cached_sources"
        const val KEY_PRODUCTS = "cached_products"
        const val KEY_TIMESTAMP = "last_modified_timestamp"
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
) {
    fun toDomain() = Game(id, name, kind, bggId, bggRating, bggRank, imageUrl)
    companion object {
        fun from(g: Game) = CachedGame(g.id, g.name, g.kind, g.bggId, g.bggRating, g.bggRank, g.imageUrl)
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
) {
    fun toDomain() = Product(id, title, imageUrl, releaseDate.asLocalDateOrNull())
    companion object {
        fun from(p: Product) = CachedProduct(p.id, p.title, p.imageUrl, p.releaseDate?.toString())
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
