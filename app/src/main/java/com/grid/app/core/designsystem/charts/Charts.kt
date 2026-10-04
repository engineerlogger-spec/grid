package com.grid.app.core.designsystem.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grid.app.core.designsystem.theme.GridText
import kotlin.math.max

@Composable
private fun rememberReveal(key: Any?, durationMillis: Int = 900): Float {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(durationMillis, easing = FastOutSlowInEasing)) }
    return progress.value
}

/**
 * Cumulative spending through the period vs the straight budget line, with a dashed projection
 * from today to the period end.
 */
@Composable
fun PaceChart(
    cumulative: List<Long>,
    length: Int,
    goalMinor: Long?,
    projectedMinor: Long,
    lineColor: Color,
    budgetColor: Color,
    projectionColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    description: String = "",
) {
    val reveal = rememberReveal(cumulative)
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        if (length <= 1) return@Canvas
        val maxY = max(max(goalMinor ?: 0L, projectedMinor), cumulative.maxOrNull() ?: 0L).coerceAtLeast(1L) * 1.08f
        val stepX = size.width / (length - 1)
        fun y(v: Long) = size.height - (v / maxY) * size.height

        repeat(4) { i ->
            val gy = size.height * i / 3f
            drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }
        if (goalMinor != null) {
            drawLine(
                budgetColor, Offset(0f, size.height), Offset(size.width, y(goalMinor)),
                strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
            )
        }
        if (cumulative.isEmpty()) return@Canvas
        val line = Path()
        cumulative.forEachIndexed { i, v -> if (i == 0) line.moveTo(0f, y(v)) else line.lineTo(i * stepX, y(v)) }
        val lastX = (cumulative.size - 1) * stepX
        val fill = Path().apply { addPath(line); lineTo(lastX, size.height); lineTo(0f, size.height); close() }

        clipRect(right = size.width * reveal) {
            drawPath(fill, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0f))))
            drawPath(line, lineColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (cumulative.size < length) {
                drawLine(
                    projectionColor, Offset(lastX, y(cumulative.last())), Offset(size.width, y(projectedMinor)),
                    strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f)), cap = StrokeCap.Round,
                )
            }
        }
        if (reveal > 0.98f) {
            drawCircle(lineColor, 5.dp.toPx(), Offset(lastX, y(cumulative.last())))
            drawCircle(lineColor.copy(alpha = 0.25f), 10.dp.toPx(), Offset(lastX, y(cumulative.last())))
        }
    }
}

data class BarPair(val label: String, val first: Long, val second: Long, val highlighted: Boolean = false)

/** Spent vs income per period, side-by-side rounded bars with labels underneath. */
@Composable
fun BarPairsChart(
    items: List<BarPair>,
    firstColor: Color,
    secondColor: Color,
    labelColor: Color,
    highlightLabelColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
) {
    val reveal = rememberReveal(items)
    val maxV = items.maxOfOrNull { max(it.first, it.second) }?.coerceAtLeast(1L)?.toFloat() ?: 1f
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            if (items.isEmpty()) return@Canvas
            val slot = size.width / items.size
            val barW = (slot * 0.26f).coerceAtMost(14.dp.toPx())
            val gap = 3.dp.toPx()
            val radius = CornerRadius(barW / 2, barW / 2)
            items.forEachIndexed { i, item ->
                val cx = slot * i + slot / 2
                listOf(item.first to firstColor, item.second to secondColor).forEachIndexed { j, (v, c) ->
                    val h = (v / maxV) * size.height * reveal
                    val x = if (j == 0) cx - gap / 2 - barW else cx + gap / 2
                    val color = if (item.highlighted) c else c.copy(alpha = 0.55f)
                    if (h > 0f) drawRoundRect(color, Offset(x, size.height - h), Size(barW, h), radius)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            items.forEach { item ->
                Text(
                    item.label, style = GridText.caps, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    color = if (item.highlighted) highlightLabelColor else labelColor,
                )
            }
        }
    }
}

/** Thin horizontal ratio bar; turns [overColor] past 100%. */
@Composable
fun RatioBar(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier, overColor: Color = color, thickness: Dp = 6.dp) {
    val reveal = rememberReveal(fraction, 700)
    Box(modifier.fillMaxWidth().height(thickness).clip(RoundedCornerShape(thickness / 2)).background(track)) {
        Box(
            Modifier
                .fillMaxWidth((fraction.coerceIn(0f, 1f) * reveal).coerceAtLeast(0.001f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(thickness / 2))
                .background(if (fraction > 1f) overColor else color),
        )
    }
}

