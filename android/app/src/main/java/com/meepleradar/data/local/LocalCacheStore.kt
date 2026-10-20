package com.meepleradar.data.local

import android.content.Context
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Product
import com.meepleradar.data.model.Source
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
            CachedDatabase(
                listings = json.decodeFromString(listingsJson),
                games = json.decodeFromString(gamesJson),
                sources = json.decodeFromString(sourcesJson),
                products = json.decodeFromString(productsJson),
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
            preferences.edit()
                .putString(KEY_LISTINGS, json.encodeToString(newListings))
                .putString(KEY_GAMES, json.encodeToString(newGames))
                .putString(KEY_SOURCES, json.encodeToString(newSources))
                .putString(KEY_PRODUCTS, json.encodeToString(newProducts))
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
