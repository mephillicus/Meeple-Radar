package com.meepleradar.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFE8B23A)
private val Indigo = Color(0xFF5B5BD6)
private val Graphite = Color(0xFF1B1B1F)
private val GraphiteSurface = Color(0xFF24242A)

val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Graphite,
    secondary = Indigo,
    background = Graphite,
    onBackground = Color(0xFFEDEDF2),
    surface = GraphiteSurface,
    onSurface = Color(0xFFEDEDF2),
)

val LightColors = lightColorScheme(
    primary = Indigo,
    secondary = Amber,
)
