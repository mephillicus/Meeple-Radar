package com.meepleradar.data.local

import android.content.Context

interface FavoriteStore {
    fun keys(): Set<String>

    fun setFavorite(key: String, favorite: Boolean)
}

class SharedPreferencesFavoriteStore(context: Context) : FavoriteStore {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun keys(): Set<String> = preferences.getStringSet(FAVORITES_KEY, emptySet()).orEmpty().toSet()

    override fun setFavorite(key: String, favorite: Boolean) {
        val updated = keys().toMutableSet()
        if (favorite) updated.add(key) else updated.remove(key)
        preferences.edit().putStringSet(FAVORITES_KEY, updated).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "meeple_radar_preferences"
        const val FAVORITES_KEY = "favorite_item_keys"
    }
}
