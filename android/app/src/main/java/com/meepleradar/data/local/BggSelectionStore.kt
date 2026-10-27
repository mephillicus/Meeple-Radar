package com.meepleradar.data.local

import android.content.Context

interface BggSelectionStore {
    fun getBggId(itemKey: String): Int?
    fun saveBggId(itemKey: String, bggId: Int)
    fun removeBggId(itemKey: String)
}

class SharedPreferencesBggSelectionStore(context: Context) : BggSelectionStore {
    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    override fun getBggId(itemKey: String): Int? =
        preferences.getInt(keyFor(itemKey), 0).takeIf { it > 0 }

    override fun saveBggId(itemKey: String, bggId: Int) {
        require(bggId > 0) { "BGG ID must be positive" }
        preferences.edit().putInt(keyFor(itemKey), bggId).apply()
    }

    override fun removeBggId(itemKey: String) {
        preferences.edit().remove(keyFor(itemKey)).apply()
    }

    private fun keyFor(itemKey: String) = "$KEY_PREFIX$itemKey"

    private companion object {
        const val PREF_NAME = "meeple_radar_bgg_selections"
        const val KEY_PREFIX = "bgg_id:"
    }
}
