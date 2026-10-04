package com.grid.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grid.app.core.designsystem.icons.CategoryIcons
import com.grid.app.core.designsystem.icons.MethodGlyph
import com.grid.app.core.designsystem.theme.CategoryColor
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.designsystem.theme.SpaceGrotesk
import com.grid.app.core.model.PaymentKind

/** Rounded square with a category icon on its tinted container. */
@Composable
fun CategoryBadge(
    iconKey: String,
    colorKey: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    onHero: Boolean = false,
) {
    val colors = GridTheme.colors
    val c: CategoryColor = if (onHero) colors.categoryOnHero(colorKey) else colors.category(colorKey)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.31f))
            .background(c.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(CategoryIcons.of(iconKey), contentDescription = null, tint = c.fg, modifier = Modifier.size(size * 0.52f))
    }
}

/** Monogram tile ("N" for Netflix) in a brand/category color. */
@Composable
fun MonogramBadge(text: String, colorKey: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val c = GridTheme.colors.category(colorKey)
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.31f)).background(c.container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.take(1).uppercase(),
            color = c.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.44f).sp,
        )
    }
}

@Composable
fun MethodBadge(kind: PaymentKind, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val colors = GridTheme.colors
    when (val glyph = MethodGlyph.of(kind)) {
        is MethodGlyph.Icon -> Icon(glyph.vector, contentDescription = null, tint = colors.muted, modifier = modifier.size(size))
        is MethodGlyph.Monogram -> Box(
            modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(colors.raised),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph.letter, color = colors.muted, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.55f).sp)
        }
    }
}
