package com.grid.app.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp

/** Foreground + tinted container for a category / brand color. */
@Immutable
data class CategoryColor(val fg: Color, val container: Color)

/** Semantic colors for Grid surfaces. Read with `GridTheme.colors`. */
@Immutable
data class GridColors(
    val isDark: Boolean,
    val background: Color,
    val tile: Color,
    val raised: Color,
    val hairline: Color,
    val gridLine: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val onAccent: Color,
    val accentText: Color,
    val danger: Color,
    val warning: Color,
    val income: Color,
    val hero: Color,
    val heroText: Color,
    val heroMuted: Color,
    val heroRaised: Color,
) {
    /** Color for a category/brand [key]. Unknown keys fall back to neutral. */
    fun category(key: String): CategoryColor {
        val base = CategoryHues[key] ?: CategoryHues.getValue("slate")
        val fg = if (isDark) base else lerp(base, Color.Black, 0.42f)
        val container = base.copy(alpha = if (isDark) 0.16f else 0.22f).compositeOver(if (isDark) tile else Color.White)
        return CategoryColor(fg, container)
    }

    /** Same as [category] but for the always-dark hero tile. */
    fun categoryOnHero(key: String): CategoryColor {
        val base = CategoryHues[key] ?: CategoryHues.getValue("slate")
        return CategoryColor(base, base.copy(alpha = 0.18f).compositeOver(hero))
    }
}

/** Category hues, tuned to read on graphite; light theme darkens them. */
val CategoryHues: Map<String, Color> = linkedMapOf(
    "orange" to Color(0xFFFF8F5A),
    "mint" to Color(0xFF5FE3B3),
    "sky" to Color(0xFF6EC1FF),
    "pink" to Color(0xFFFF8FC7),
    "magenta" to Color(0xFFE08CFF),
    "amber" to Color(0xFFFFB547),
    "violet" to Color(0xFFB69CFF),
    "sand" to Color(0xFFD9AE80),
    "red" to Color(0xFFFF6B7A),
    "indigo" to Color(0xFF8C9EFF),
    "teal" to Color(0xFF4FD1D9),
    "blue" to Color(0xFF5B9BFF),
    "yellow" to Color(0xFFF2D04F),
    "peach" to Color(0xFFFFB4A2),
    "slate" to Color(0xFF9BB0C1),
    "gray" to Color(0xFFA3ABAF),
    "lime" to Color(0xFFC8F560),
    "green" to Color(0xFF7BE495),
)

internal val DarkGridColors = GridColors(
    isDark = true,
    background = Palette.Ink,
    tile = Palette.DarkTile,
    raised = Palette.DarkRaised,
    hairline = Palette.DarkHairline,
    gridLine = Color.White.copy(alpha = 0.028f),
    text = Palette.DarkText,
    muted = Palette.DarkMuted,
    faint = Palette.DarkFaint,
    accent = Palette.Lime,
    onAccent = Palette.Ink,
    accentText = Palette.Lime,
    danger = Palette.Coral,
    warning = Palette.Amber,
    income = Palette.Sky,
    hero = Palette.DarkTile,
    heroText = Palette.DarkText,
    heroMuted = Palette.DarkMuted,
    heroRaised = Palette.DarkRaised,
)

internal val LightGridColors = GridColors(
    isDark = false,
    background = Palette.Paper,
    tile = Palette.LightTile,
    raised = Palette.LightRaised,
    hairline = Palette.LightHairline,
    gridLine = Color.Black.copy(alpha = 0.038f),
    text = Palette.LightText,
    muted = Palette.LightMuted,
    faint = Palette.LightFaint,
    accent = Palette.Lime,
    onAccent = Palette.Ink,
    accentText = Palette.LimeDeep,
    danger = Palette.CoralDeep,
    warning = Palette.AmberDeep,
    income = Palette.SkyDeep,
    // The hero tile stays graphite in light mode — the visual anchor of Home.
    hero = Palette.Ink,
    heroText = Palette.DarkText,
    heroMuted = Palette.DarkMuted,
    heroRaised = Color(0xFF1D2224),
)

/** Material You variant: Grid's structure, wallpaper-derived colors. */
internal fun dynamicGridColors(scheme: ColorScheme, dark: Boolean): GridColors {
    val base = if (dark) DarkGridColors else LightGridColors
    return base.copy(
        background = scheme.surface,
        tile = scheme.surfaceContainerLow,
        raised = scheme.surfaceContainerHigh,
        hairline = scheme.outlineVariant,
        text = scheme.onSurface,
        muted = scheme.onSurfaceVariant,
        accent = scheme.primary,
        onAccent = scheme.onPrimary,
        accentText = scheme.primary,
        hero = if (dark) scheme.surfaceContainerHigh else scheme.inverseSurface,
        heroText = if (dark) scheme.onSurface else scheme.inverseOnSurface,
        heroMuted = if (dark) scheme.onSurfaceVariant else scheme.inverseOnSurface.copy(alpha = 0.7f),
        heroRaised = if (dark) scheme.surfaceContainerHighest else scheme.inverseSurface.copy(alpha = 0.85f).compositeOver(scheme.inverseOnSurface),
    )
}

val LocalGridColors = staticCompositionLocalOf { DarkGridColors }
