package com.meepleradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.meepleradar.ui.nav.MeepleRadarNav
import com.meepleradar.ui.theme.MeepleRadarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MeepleRadarApp).container
        setContent {
            MeepleRadarTheme {
                MeepleRadarNav(container)
            }
        }
    }
}
