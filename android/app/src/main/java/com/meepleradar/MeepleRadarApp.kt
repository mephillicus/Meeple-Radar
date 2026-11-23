package com.meepleradar

import android.app.Application

class MeepleRadarApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        val prefs = container.notificationPreferencesStore.load()
        if (prefs.backgroundSyncEnabled) {
            container.syncWorkScheduler.syncSchedule(
                enabled = true,
                intervalHours = prefs.syncIntervalHours.toLong(),
                wifiOnly = prefs.syncWifiOnly,
            )
        }
    }
}
