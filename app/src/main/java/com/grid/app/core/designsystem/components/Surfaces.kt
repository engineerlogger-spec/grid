package com.grid.app.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme

val TileShape = RoundedCornerShape(22.dp)

/** The faint 24dp grid behind every screen — Grid's signature texture. */
fun Modifier.gridBackground(lineColor: Color, cell: Dp = 24.dp): Modifier = drawBehind {
    val step = cell.toPx()
    val stroke = 1f
    var x = 0f
    while (x <= size.width) {
        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
        x += step
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), stroke)
        y += step
    }
}

/** Full-screen background: theme ground color plus the grid texture. */
@Composable
fun GridSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = GridTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .gridBackground(colors.gridLine),
        content = content,
    )
}

/** A bento tile: rounded, hairline border, no elevation. */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = GridTheme.colors.tile,
    borderColor: Color = GridTheme.colors.hairline,
    contentColor: Color = GridTheme.colors.text,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = if (borderColor == Color.Transparent) null else BorderStroke(1.dp, borderColor)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = TileShape, color = color, contentColor = contentColor, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = TileShape, color = color, contentColor = contentColor, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** The dark anchor tile at the top of Home (graphite in both themes). */
@Composable
fun HeroTile(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = GridTheme.colors
    Tile(
        modifier = modifier,
        onClick = onClick,
        color = colors.hero,
        borderColor = if (colors.isDark) colors.hairline else Color.Transparent,
        contentColor = colors.heroText,
        contentPadding = PaddingValues(18.dp),
        content = content,
    )
}

/** Small uppercase label, e.g. "LEFT TO SPEND". */
@Composable
fun CapsLabel(text: String, modifier: Modifier = Modifier, color: Color = GridTheme.colors.muted) {
    Text(text.uppercase(), modifier = modifier, style = GridText.caps, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Section title with an optional trailing action ("See all"). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsLabel(title)
        if (action != null && onAction != null) {
            Text(
                text = action,
                style = GridText.caps,
                color = GridTheme.colors.accentText,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .then(Modifier.clickableNoIndication(onAction)),
            )
        }
    }
}
