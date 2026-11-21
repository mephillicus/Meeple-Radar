package com.meepleradar.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationPreferences(
    val backgroundSyncEnabled: Boolean = false,
    val notifyBackInStock: Boolean = true,
    val notifyPriceDrop: Boolean = true,
    val notifyNewExpansion: Boolean = true,
    val notifyNewsDigest: Boolean = false,
)

interface NotificationPreferencesStore {
    val preferencesFlow: StateFlow<NotificationPreferences>

    fun load(): NotificationPreferences

    fun update(transform: (NotificationPreferences) -> NotificationPreferences): NotificationPreferences

    fun notifiedAlertIds(): Set<Long>

    fun markAlertsNotified(ids: Collection<Long>)

    fun lastNotifiedNewsTimestamp(): Long

    fun setLastNotifiedNewsTimestamp(timestampMs: Long)
}

class SharedPreferencesNotificationPreferencesStore(
    context: Context,
) : NotificationPreferencesStore {

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val _preferencesFlow = MutableStateFlow(readFromPrefs())
    override val preferencesFlow: StateFlow<NotificationPreferences> = _preferencesFlow.asStateFlow()

    override fun load(): NotificationPreferences = _preferencesFlow.value

    override fun update(transform: (NotificationPreferences) -> NotificationPreferences): NotificationPreferences {
        val next = transform(_preferencesFlow.value)
        prefs.edit()
            .putBoolean(KEY_BG_SYNC, next.backgroundSyncEnabled)
            .putBoolean(KEY_BACK_IN_STOCK, next.notifyBackInStock)
            .putBoolean(KEY_PRICE_DROP, next.notifyPriceDrop)
            .putBoolean(KEY_NEW_EXPANSION, next.notifyNewExpansion)
            .putBoolean(KEY_NEWS_DIGEST, next.notifyNewsDigest)
            .apply()
        _preferencesFlow.value = next
        return next
    }

    override fun notifiedAlertIds(): Set<Long> {
        val raw = prefs.getString(KEY_NOTIFIED_IDS, "").orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    }

    override fun markAlertsNotified(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val merged = (notifiedAlertIds() + ids)
            .sortedDescending()
            .take(MAX_TRACKED_IDS)
        prefs.edit()
            .putString(KEY_NOTIFIED_IDS, merged.joinToString(","))
            .apply()
    }

    override fun lastNotifiedNewsTimestamp(): Long =
        prefs.getLong(KEY_LAST_NEWS_TS, 0L)

    override fun setLastNotifiedNewsTimestamp(timestampMs: Long) {
        prefs.edit()
            .putLong(KEY_LAST_NEWS_TS, timestampMs)
            .apply()
    }

    private fun readFromPrefs() = NotificationPreferences(
        backgroundSyncEnabled = prefs.getBoolean(KEY_BG_SYNC, false),
        notifyBackInStock = prefs.getBoolean(KEY_BACK_IN_STOCK, true),
        notifyPriceDrop = prefs.getBoolean(KEY_PRICE_DROP, true),
        notifyNewExpansion = prefs.getBoolean(KEY_NEW_EXPANSION, true),
        notifyNewsDigest = prefs.getBoolean(KEY_NEWS_DIGEST, false),
    )

    private companion object {
        const val PREF_NAME = "meeple_radar_notifications"
        const val KEY_BG_SYNC = "background_sync_enabled"
        const val KEY_BACK_IN_STOCK = "notify_back_in_stock"
        const val KEY_PRICE_DROP = "notify_price_drop"
        const val KEY_NEW_EXPANSION = "notify_new_expansion"
        const val KEY_NEWS_DIGEST = "notify_news_digest"
        const val KEY_NOTIFIED_IDS = "notified_alert_ids"
        const val KEY_LAST_NEWS_TS = "last_notified_news_ts"
        const val MAX_TRACKED_IDS = 200
    }
}
