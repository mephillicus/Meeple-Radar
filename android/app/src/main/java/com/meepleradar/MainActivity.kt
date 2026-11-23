package com.meepleradar

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meepleradar.data.local.AppThemeMode
import com.meepleradar.notifications.EXTRA_OPEN_DETAIL_KEY
import com.meepleradar.ui.nav.MeepleRadarNav
import com.meepleradar.ui.theme.MeepleRadarTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val pendingDetailKey = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        handleNotificationIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val container = (application as MeepleRadarApp).container
        setContent {
            val detailKey by pendingDetailKey.collectAsStateWithLifecycle()
            val prefs by container.notificationPreferencesStore.preferencesFlow.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val isDark = when (prefs.themeMode) {
                AppThemeMode.SYSTEM -> systemDark
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            SideEffect {
                val barStyle = if (isDark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(
                    statusBarStyle = barStyle,
                    navigationBarStyle = barStyle,
                )
            }
            MeepleRadarTheme(
                darkTheme = isDark,
                dynamicColor = prefs.dynamicColors,
            ) {
                MeepleRadarNav(
                    container = container,
                    pendingDetailKey = detailKey,
                    onDetailKeyConsumed = { pendingDetailKey.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_OPEN_DETAIL_KEY)?.takeIf(String::isNotBlank) ?: return
        pendingDetailKey.value = key
        intent.removeExtra(EXTRA_OPEN_DETAIL_KEY)
    }
}
