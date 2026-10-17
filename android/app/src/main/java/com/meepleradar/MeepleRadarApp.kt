package com.meepleradar

import android.app.Application

class MeepleRadarApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
