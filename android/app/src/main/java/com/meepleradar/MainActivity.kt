package com.meepleradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.meepleradar.ui.nav.MeepleRadarNav
import com.meepleradar.ui.theme.MeepleRadarTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MeepleRadarApp).container
        setContent {
            MeepleRadarTheme {
                var isSplashActive by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    delay(800L)
                    isSplashActive = false
                }

                if (isSplashActive) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_icon_src),
                            contentDescription = "Meeple Radar",
                            modifier = Modifier
                                .size(180.dp)
                                .clip(RoundedCornerShape(32.dp)),
                        )
                    }
                } else {
                    MeepleRadarNav(container)
                }
            }
        }
    }
}
