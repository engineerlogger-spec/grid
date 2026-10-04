package com.grid.app.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/** Raw "Graphite & Lime" palette. Screens use [GridColors], never these directly. */
internal object Palette {
    val Ink = Color(0xFF0B0D0E)
    val Paper = Color(0xFFF3F2EC)
    val White = Color(0xFFFFFFFF)

    // Dark surfaces
    val DarkTile = Color(0xFF15191B)
    val DarkRaised = Color(0xFF1B2023)
    val DarkHairline = Color(0xFF232A2E)
    val DarkOutline = Color(0xFF3A4247)
    val DarkText = Color(0xFFF2F4F3)
    val DarkMuted = Color(0xFF8E979B)
    val DarkFaint = Color(0xFF5C6569)

    // Light surfaces
    val LightTile = Color(0xFFFFFFFF)
    val LightRaised = Color(0xFFF8F7F2)
    val LightHairline = Color(0xFFE3E1D8)
    val LightOutline = Color(0xFFC9C6BB)
    val LightText = Color(0xFF111315)
    val LightMuted = Color(0xFF62696D)
    val LightFaint = Color(0xFFA2A7A9)

    // Accents
    val Lime = Color(0xFFC8F560)
    val LimeDeep = Color(0xFF4E7D00)
    val Coral = Color(0xFFFF6B5A)
    val CoralDeep = Color(0xFFD93D2B)
    val Amber = Color(0xFFFFB547)
    val AmberDeep = Color(0xFFB26A00)
    val Sky = Color(0xFF6EC1FF)
    val SkyDeep = Color(0xFF2E7FD0)
}
