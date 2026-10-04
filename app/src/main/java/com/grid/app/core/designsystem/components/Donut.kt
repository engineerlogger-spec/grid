package com.grid.app.core.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class DonutSlice(val fraction: Float, val color: Color)

/** Ring chart with small gaps between slices; sweeps in on first show. */
@Composable
fun Donut(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    thickness: Dp = 8.dp,
    trackColor: Color,
    gapDegrees: Float = 4f,
) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) { sweep.animateTo(1f, tween(700)) }
    Canvas(modifier.size(size)) {
        val strokePx = thickness.toPx()
        val inset = strokePx / 2
        val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
        val topLeft = Offset(inset, inset)
        drawArc(trackColor, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(strokePx))
        val total = slices.sumOf { it.fraction.toDouble() }.toFloat().coerceAtLeast(1f)
        val gap = if (slices.size > 1) gapDegrees else 0f
        var start = -90f
        slices.forEach { slice ->
            val angle = 360f * slice.fraction / total * sweep.value
            val visible = (angle - gap).coerceAtLeast(0.5f)
            if (slice.fraction > 0f) {
                drawArc(slice.color, start + gap / 2, visible, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(strokePx, cap = StrokeCap.Butt))
            }
            start += angle
        }
    }
}
