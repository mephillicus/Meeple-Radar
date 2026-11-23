package com.meepleradar.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode(val label: String) {
    SYSTEM("Sistema"),
    LIGHT("Chiaro"),
    DARK("Scuro"),
}

data class NotificationPreferences(
    val backgroundSyncEnabled: Boolean = false,
    val notifyBackInStock: Boolean = true,
    val notifyPriceDrop: Boolean = true,
    val notifyNewExpansion: Boolean = true,
    val notifyNewsDigest: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val dynamicColors: Boolean = false,
    val syncIntervalHours: Int = 12,
    val syncWifiOnly: Boolean = false,
    val minPriceDropPct: Int = 0,
    val quietHoursEnabled: Boolean = false,
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
            .putString(KEY_THEME_MODE, next.themeMode.name)
            .putBoolean(KEY_DYNAMIC_COLORS, next.dynamicColors)
            .putInt(KEY_SYNC_INTERVAL_HOURS, next.syncIntervalHours)
            .putBoolean(KEY_SYNC_WIFI_ONLY, next.syncWifiOnly)
            .putInt(KEY_MIN_PRICE_DROP_PCT, next.minPriceDropPct)
            .putBoolean(KEY_QUIET_HOURS_ENABLED, next.quietHoursEnabled)
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

    private fun readFromPrefs(): NotificationPreferences {
        val rawTheme = prefs.getString(KEY_THEME_MODE, AppThemeMode.DARK.name)
        val themeMode = AppThemeMode.entries.firstOrNull { it.name == rawTheme } ?: AppThemeMode.DARK
        val interval = prefs.getInt(KEY_SYNC_INTERVAL_HOURS, 12).takeIf { it in VALID_SYNC_INTERVALS } ?: 12
        val minDrop = prefs.getInt(KEY_MIN_PRICE_DROP_PCT, 0).takeIf { it in VALID_PRICE_DROP_THRESHOLDS } ?: 0
        return NotificationPreferences(
            backgroundSyncEnabled = prefs.getBoolean(KEY_BG_SYNC, false),
            notifyBackInStock = prefs.getBoolean(KEY_BACK_IN_STOCK, true),
            notifyPriceDrop = prefs.getBoolean(KEY_PRICE_DROP, true),
            notifyNewExpansion = prefs.getBoolean(KEY_NEW_EXPANSION, true),
            notifyNewsDigest = prefs.getBoolean(KEY_NEWS_DIGEST, false),
            themeMode = themeMode,
            dynamicColors = prefs.getBoolean(KEY_DYNAMIC_COLORS, false),
            syncIntervalHours = interval,
            syncWifiOnly = prefs.getBoolean(KEY_SYNC_WIFI_ONLY, false),
            minPriceDropPct = minDrop,
            quietHoursEnabled = prefs.getBoolean(KEY_QUIET_HOURS_ENABLED, false),
        )
    }

    private companion object {
        const val PREF_NAME = "meeple_radar_notifications"
        const val KEY_BG_SYNC = "background_sync_enabled"
        const val KEY_BACK_IN_STOCK = "notify_back_in_stock"
        const val KEY_PRICE_DROP = "notify_price_drop"
        const val KEY_NEW_EXPANSION = "notify_new_expansion"
        const val KEY_NEWS_DIGEST = "notify_news_digest"
        const val KEY_THEME_MODE = "app_theme_mode"
        const val KEY_DYNAMIC_COLORS = "app_dynamic_colors"
        const val KEY_SYNC_INTERVAL_HOURS = "sync_interval_hours"
        const val KEY_SYNC_WIFI_ONLY = "sync_wifi_only"
        const val KEY_MIN_PRICE_DROP_PCT = "min_price_drop_pct"
        const val KEY_QUIET_HOURS_ENABLED = "quiet_hours_enabled"
        const val KEY_NOTIFIED_IDS = "notified_alert_ids"
        const val KEY_LAST_NEWS_TS = "last_notified_news_ts"
        const val MAX_TRACKED_IDS = 200
        val VALID_SYNC_INTERVALS = setOf(6, 12, 24)
        val VALID_PRICE_DROP_THRESHOLDS = setOf(0, 10, 20, 30)
    }
}
