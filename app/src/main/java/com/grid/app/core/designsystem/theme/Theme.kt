package com.grid.app.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.grid.app.core.model.ThemeMode

@Composable
fun isGridDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun GridTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isGridDark(themeMode)
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme: ColorScheme
    val colors: GridColors
    if (useDynamic) {
        val context = LocalContext.current
        scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        colors = dynamicGridColors(scheme, dark)
    } else {
        colors = if (dark) DarkGridColors else LightGridColors
        scheme = colors.toMaterialScheme()
    }
    CompositionLocalProvider(LocalGridColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = GridTypography, shapes = GridShapes) {
            // Icons and text outside a Surface (back/‹/› buttons on full screens) default to black otherwise.
            CompositionLocalProvider(LocalContentColor provides colors.text, content = content)
        }
    }
}

/** Accessors: `GridTheme.colors.tile`, mirroring `MaterialTheme.colorScheme`. */
object GridTheme {
    val colors: GridColors
        @Composable @ReadOnlyComposable get() = LocalGridColors.current
}

internal val GridShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private fun GridColors.toMaterialScheme(): ColorScheme {
    val accentContainer = accent.copy(alpha = 0.18f).compositeOver(tile)
    return if (isDark) darkColorScheme(
        primary = accent, onPrimary = onAccent,
        primaryContainer = accentContainer, onPrimaryContainer = accent,
        secondary = income, onSecondary = onAccent,
        secondaryContainer = raised, onSecondaryContainer = text,
        tertiary = warning, onTertiary = onAccent,
        background = background, onBackground = text,
        surface = background, onSurface = text,
        surfaceVariant = raised, onSurfaceVariant = muted,
        surfaceContainerLowest = background, surfaceContainerLow = tile, surfaceContainer = tile,
        surfaceContainerHigh = raised, surfaceContainerHighest = hairline,
        surfaceBright = raised, surfaceDim = background,
        outline = Palette.DarkOutline, outlineVariant = hairline,
        error = danger, onError = onAccent,
        inverseSurface = text, inverseOnSurface = background, inversePrimary = Palette.LimeDeep,
        scrim = Palette.Ink,
    ) else lightColorScheme(
        // Light mode: ink is the action color (lime text on ink buttons), lime is a fill.
        primary = Palette.Ink, onPrimary = Palette.Lime,
        primaryContainer = accent, onPrimaryContainer = Palette.Ink,
        secondary = income, onSecondary = Palette.White,
        secondaryContainer = raised, onSecondaryContainer = text,
        tertiary = warning, onTertiary = Palette.White,
        background = background, onBackground = text,
        surface = background, onSurface = text,
        surfaceVariant = raised, onSurfaceVariant = muted,
        surfaceContainerLowest = Palette.White, surfaceContainerLow = tile, surfaceContainer = tile,
        surfaceContainerHigh = raised, surfaceContainerHighest = hairline,
        surfaceBright = Palette.White, surfaceDim = hairline,
        outline = Palette.LightOutline, outlineVariant = hairline,
        error = danger, onError = Palette.White,
        inverseSurface = Palette.Ink, inverseOnSurface = Palette.DarkText, inversePrimary = Palette.Lime,
        scrim = Palette.Ink,
    )
}
