package com.grid.app.core.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.money.MoneyFormatter
import java.util.Locale

val LocalMoneyFormatter = staticCompositionLocalOf { MoneyFormatter(Locale.getDefault()) }

/** True while the user has "hide amounts" on — every [MoneyText] masks itself. */
val LocalHideAmounts = compositionLocalOf { false }

/**
 * An amount in minor units: big whole part, smaller dimmed fraction (".50"), tabular figures.
 * Animates between values (exact value shown once the animation settles).
 */
@Composable
fun MoneyText(
    minor: Long,
    currency: String,
    modifier: Modifier = Modifier,
    style: TextStyle = GridText.moneyMedium,
    color: Color = LocalContentColor.current,
    fractionColor: Color = color.copy(alpha = 0.5f),
    fractionScale: Float = 0.62f,
    signed: Boolean = false,
    masked: Boolean = LocalHideAmounts.current,
    animate: Boolean = true,
    maxLines: Int = 1,
) {
    val formatter = LocalMoneyFormatter.current
    val anim = remember { Animatable(minor.toFloat()) }
    LaunchedEffect(minor, animate) {
        if (animate) anim.animateTo(minor.toFloat(), tween(durationMillis = 450)) else anim.snapTo(minor.toFloat())
    }
    val shown = if (anim.isRunning) anim.value.toLong() else minor
    val parts = formatter.parts(shown, currency, masked = masked, signed = signed)
    val fractionSize: TextUnit = if (style.fontSize.isSpecified) style.fontSize * fractionScale else style.fontSize
    val spoken = formatter.format(minor, currency, signed = signed, masked = masked)
    Text(
        text = buildAnnotatedString {
            append(parts.whole)
            if (parts.fraction.isNotEmpty()) {
                withStyle(SpanStyle(color = fractionColor, fontSize = fractionSize)) { append(parts.fraction) }
            }
        },
        modifier = modifier.semantics { contentDescription = spoken },
        style = style,
        color = color,
        maxLines = maxLines,
    )
}

/** Plain single-style amount ("€12.50"), for dense lists. */
@Composable
fun AmountText(
    minor: Long,
    currency: String,
    modifier: Modifier = Modifier,
    style: TextStyle = GridText.moneySmall,
    color: Color = LocalContentColor.current,
    signed: Boolean = false,
    compact: Boolean = false,
) {
    val formatter = LocalMoneyFormatter.current
    val masked = LocalHideAmounts.current
    val text = when {
        compact -> (if (signed && minor > 0) "+" else "") + formatter.compact(minor, currency, masked)
        else -> formatter.format(minor, currency, signed = signed, masked = masked)
    }
    Text(text, modifier = modifier, style = style, color = color, maxLines = 1)
}
