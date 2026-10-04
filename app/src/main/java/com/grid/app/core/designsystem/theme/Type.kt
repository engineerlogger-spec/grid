package com.grid.app.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.grid.app.R

private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val SpaceGrotesk = FontFamily(
    variable(R.font.space_grotesk, 400),
    variable(R.font.space_grotesk, 500),
    variable(R.font.space_grotesk, 600),
    variable(R.font.space_grotesk, 700),
)

val Inter = FontFamily(
    variable(R.font.inter, 400),
    variable(R.font.inter, 500),
    variable(R.font.inter, 600),
    variable(R.font.inter, 700),
)

private fun display(size: Int, weight: FontWeight = FontWeight.SemiBold) = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = weight, fontSize = size.sp, lineHeight = (size * 1.15).sp, letterSpacing = (-0.02).em,
)

private fun body(size: Int, weight: FontWeight = FontWeight.Normal, line: Double = 1.45, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter, fontWeight = weight, fontSize = size.sp, lineHeight = (size * line).sp, letterSpacing = tracking.em,
)

internal val GridTypography = Typography(
    displayLarge = display(52),
    displayMedium = display(40),
    displaySmall = display(34),
    headlineLarge = display(30),
    headlineMedium = display(24),
    headlineSmall = display(22),
    titleLarge = display(20),
    titleMedium = body(16, FontWeight.SemiBold, 1.35),
    titleSmall = body(14, FontWeight.SemiBold, 1.35),
    bodyLarge = body(16),
    bodyMedium = body(14),
    bodySmall = body(12),
    labelLarge = body(14, FontWeight.Medium, 1.3),
    labelMedium = body(12, FontWeight.Medium, 1.3),
    labelSmall = body(11, FontWeight.Medium, 1.3, 0.02),
)

/** Money and label styles that aren't part of the M3 scale. Numbers use tabular figures so they don't jitter. */
@Immutable
object GridText {
    private fun money(size: Int, weight: FontWeight = FontWeight.SemiBold) =
        display(size, weight).copy(fontFeatureSettings = "tnum")

    val moneyHero = money(42)
    val moneyLarge = money(28)
    val moneyMedium = money(20)
    val moneySmall = money(15, FontWeight.Medium)
    val moneyTiny = money(13, FontWeight.Medium)

    /** Small uppercase section label ("LEFT TO SPEND"). Apply `.uppercase()` to the text. */
    val caps = body(11, FontWeight.SemiBold, 1.2, 0.08)
}
