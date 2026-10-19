package com.meepleradar.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val Violet80 = Color(0xFFA78BFA)
val Violet40 = Color(0xFF6D28D9)
val VioletContainerDark = Color(0xFF3B0764)
val VioletContainerLight = Color(0xFFEDE9FE)

val Orange80 = Color(0xFFFB923C)
val Orange40 = Color(0xFFEA580C)
val OrangeContainerDark = Color(0xFF431407)
val OrangeContainerLight = Color(0xFFFFEDD5)

val Emerald80 = Color(0xFF34D399)
val Emerald40 = Color(0xFF059669)

val SlateBackground = Color(0xFF0F172A)
val SlateSurface = Color(0xFF1E293B)
val SlateSurfaceHigh = Color(0xFF334155)
val SlateBorder = Color(0xFF475569)

val BggGoldBackground = Color(0xFFFEF3C7)
val BggGoldText = Color(0xFFB45309)
val BggGoldDarkBackground = Color(0xFF451A03)
val BggGoldDarkText = Color(0xFFFDE68A)

val DarkColors = darkColorScheme(
    primary = Violet80,
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = VioletContainerDark,
    onPrimaryContainer = Color(0xFFF3E8FF),
    secondary = Orange80,
    onSecondary = Color(0xFF451A03),
    secondaryContainer = OrangeContainerDark,
    onSecondaryContainer = Color(0xFFFFEDD5),
    tertiary = Emerald80,
    background = SlateBackground,
    onBackground = Color(0xFFF8FAFC),
    surface = SlateSurface,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = SlateSurfaceHigh,
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = SlateBorder,
    outlineVariant = Color(0xFF334155),
)

val LightColors = lightColorScheme(
    primary = Violet40,
    onPrimary = Color.White,
    primaryContainer = VioletContainerLight,
    onPrimaryContainer = Color(0xFF2E1065),
    secondary = Orange40,
    onSecondary = Color.White,
    secondaryContainer = OrangeContainerLight,
    onSecondaryContainer = Color(0xFF7C2D12),
    tertiary = Emerald40,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
)
