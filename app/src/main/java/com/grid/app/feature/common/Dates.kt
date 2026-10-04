package com.grid.app.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.grid.app.R
import com.grid.app.core.designsystem.components.currentLocale
import com.grid.app.core.time.BudgetPeriod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/** "October", or "October 2025" when not the current year. */
fun BudgetPeriod.title(today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val month = labelMonth.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
    return if (labelMonth.year == today.year) month else "$month ${labelMonth.year}"
}

/** Day header for lists: Today / Yesterday / "Mon 12 Oct". */
@Composable
fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.activity_today)
    today.minusDays(1) -> stringResource(R.string.activity_yesterday)
    else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", currentLocale()))
}

fun shortDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))

fun timeOfDay(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
