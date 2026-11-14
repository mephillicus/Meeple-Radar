package com.meepleradar.data.local

import android.content.Context

interface SeenStore {
    fun keys(): Set<String>

    fun setSeen(key: String, seen: Boolean)
}

class SharedPreferencesSeenStore(context: Context) : SeenStore {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun keys(): Set<String> = preferences.getStringSet(SEEN_KEY, emptySet()).orEmpty().toSet()

    override fun setSeen(key: String, seen: Boolean) {
        val updated = keys().toMutableSet()
        if (seen) updated.add(key) else updated.remove(key)
        preferences.edit().putStringSet(SEEN_KEY, updated).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "meeple_radar_preferences"
        const val SEEN_KEY = "seen_item_keys"
    }
}
