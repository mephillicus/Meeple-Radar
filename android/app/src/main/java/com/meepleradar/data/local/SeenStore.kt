package com.meepleradar.data.local

import android.content.Context

interface SeenStore {
    fun keys(): Set<String>

    fun setSeen(key: String, seen: Boolean)

    fun setAllSeen(keys: Collection<String>) {
        keys.forEach { setSeen(it, true) }
    }
}

class SharedPreferencesSeenStore(context: Context) : SeenStore {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile
    private var cachedKeys: Set<String>? = null

    override fun keys(): Set<String> {
        cachedKeys?.let { return it }
        return synchronized(lock) {
            cachedKeys ?: preferences.getStringSet(SEEN_KEY, emptySet()).orEmpty().toSet().also {
                cachedKeys = it
            }
        }
    }

    override fun setSeen(key: String, seen: Boolean) {
        synchronized(lock) {
            val current = keys()
            val alreadyMatches = if (seen) key in current else key !in current
            if (alreadyMatches) return
            val updated = current.toMutableSet()
            if (seen) updated.add(key) else updated.remove(key)
            val immutable = updated.toSet()
            cachedKeys = immutable
            preferences.edit().putStringSet(SEEN_KEY, immutable).apply()
        }
    }

    override fun setAllSeen(keys: Collection<String>) {
        if (keys.isEmpty()) return
        synchronized(lock) {
            val current = keys()
            val updated = current.toMutableSet()
            if (!updated.addAll(keys)) return
            val immutable = updated.toSet()
            cachedKeys = immutable
            preferences.edit().putStringSet(SEEN_KEY, immutable).apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "meeple_radar_preferences"
        const val SEEN_KEY = "seen_item_keys"
    }
}
