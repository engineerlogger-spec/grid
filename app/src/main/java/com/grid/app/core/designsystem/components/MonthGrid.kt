package com.grid.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.insights.DayState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/**
 * One cell of the month grid. [intensity] (0–1) is how much of the day's allowance was used;
 * [over] marks days above the allowance (drawn in coral).
 */
data class MonthCellUi(val date: LocalDate, val state: DayState, val intensity: Float, val over: Boolean)

private val Coral = Color(0xFFFF6B5A)

/**
 * Grid's signature element: every day of the budget period as a cell, aligned to weekdays,
 * colored by spending vs the daily allowance. Drawn on the dark hero tile.
 */
@Composable
fun MonthGrid(
    cells: List<MonthCellUi>,
    firstDayOfWeek: DayOfWeek,
    modifier: Modifier = Modifier,
    showWeekdays: Boolean = true,
    onDayClick: ((LocalDate) -> Unit)? = null,
) {
    if (cells.isEmpty()) return
    val colors = GridTheme.colors
    val locale = currentLocale()
    val leading = (cells.first().date.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val slots: List<MonthCellUi?> = List(leading) { null } + cells
    val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", locale)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showWeekdays) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(7) { i ->
                    Text(
                        text = firstDayOfWeek.plus(i.toLong()).getDisplayName(TextStyle.NARROW, locale),
                        style = GridText.caps,
                        color = colors.heroMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        slots.chunked(7).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { colIndex, cell ->
                    val index = rowIndex * 7 + colIndex
                    if (cell == null) {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val alpha = rememberStaggeredAlpha(index)
                        val shape = RoundedCornerShape(5.dp)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .alpha(alpha)
                                .clip(shape)
                                .background(cellColor(cell, colors.heroRaised, colors.accent))
                                .then(
                                    if (cell.state == DayState.TODAY) Modifier.border(1.5.dp, colors.heroText, shape) else Modifier,
                                )
                                .then(if (onDayClick != null && cell.state != DayState.FUTURE) Modifier.clickable { onDayClick(cell.date) } else Modifier)
                                .semantics { contentDescription = cell.date.format(dayFormat) },
                            contentAlignment = Alignment.Center,
                        ) {}
                    }
                }
                repeat(7 - row.size) { Box(Modifier.weight(1f).aspectRatio(1f)) }
            }
        }
    }
}

private fun cellColor(cell: MonthCellUi, base: Color, accent: Color): Color = when {
    cell.state == DayState.FUTURE -> base
    cell.over -> lerp(base, Coral, 0.55f + 0.45f * cell.intensity.coerceIn(0f, 1f))
    cell.intensity <= 0f -> lerp(base, accent, 0.07f)
    else -> lerp(base, accent, 0.22f + 0.78f * cell.intensity.coerceIn(0f, 1f))
}

/** Same grid, compact and without interaction — used in onboarding and empty states as an illustration. */
@Composable
fun MonthGridIllustration(modifier: Modifier = Modifier) {
    val today = LocalDate.of(2026, 10, 14)
    val pattern = floatArrayOf(0.3f, 0.6f, 1f, 0.45f, 1.6f, 0.3f, 0.6f, 0.8f, 0.2f, 1f, 1.3f, 0.6f, 0.3f, 0.9f)
    val cells = (0 until 28).map { i ->
        val date = today.withDayOfMonth(1).plusDays(i.toLong())
        val state = when { i < 13 -> DayState.PAST; i == 13 -> DayState.TODAY; else -> DayState.FUTURE }
        val ratio = pattern.getOrElse(i) { 0f }
        MonthCellUi(date, state, intensity = if (ratio > 1f) ratio - 1f else ratio, over = ratio > 1f)
    }
    MonthGrid(cells, DayOfWeek.MONDAY, modifier.fillMaxWidth(), showWeekdays = false)
}
