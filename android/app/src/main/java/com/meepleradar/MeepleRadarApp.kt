package com.meepleradar

import android.app.Application

class MeepleRadarApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        if (container.notificationPreferencesStore.load().backgroundSyncEnabled) {
            container.syncWorkScheduler.syncSchedule(true)
        }
    }
}
