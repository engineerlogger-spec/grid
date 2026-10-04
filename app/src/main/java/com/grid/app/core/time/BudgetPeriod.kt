package com.grid.app.core.time

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A budget period: [start] inclusive to [endExclusive] exclusive (normally one month). */
data class BudgetPeriod(val start: LocalDate, val endExclusive: LocalDate) {

    val length: Int get() = ChronoUnit.DAYS.between(start, endExclusive).toInt()

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && date.isBefore(endExclusive)

    /** 1-based day of the period. */
    fun dayNumber(date: LocalDate): Int = ChronoUnit.DAYS.between(start, date).toInt() + 1

    /** Days remaining including [today]; the whole length before the period, 0 after it. */
    fun daysLeft(today: LocalDate): Int = when {
        today.isBefore(start) -> length
        !today.isBefore(endExclusive) -> 0
        else -> ChronoUnit.DAYS.between(today, endExclusive).toInt()
    }

    /** The month the period is named after: the month holding most of its days. */
    val labelMonth: YearMonth
        get() = if (start.dayOfMonth <= 15) YearMonth.from(start) else YearMonth.from(start.plusMonths(1))

    fun days(): List<LocalDate> = (0 until length).map { start.plusDays(it.toLong()) }

    fun startMillis(zone: ZoneId): Long = start.atStartOfDay(zone).toInstant().toEpochMilli()
    fun endMillis(zone: ZoneId): Long = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
}

object BudgetPeriods {
    const val MIN_START_DAY = 1
    const val MAX_START_DAY = 28

    fun periodFor(date: LocalDate, startDay: Int): BudgetPeriod {
        val day = startDay.coerceIn(MIN_START_DAY, MAX_START_DAY)
        val start = if (date.dayOfMonth >= day) date.withDayOfMonth(day) else date.minusMonths(1).withDayOfMonth(day)
        return BudgetPeriod(start, start.plusMonths(1))
    }

    fun previous(period: BudgetPeriod, startDay: Int): BudgetPeriod = periodFor(period.start.minusDays(1), startDay)

    fun next(period: BudgetPeriod, startDay: Int): BudgetPeriod = periodFor(period.endExclusive, startDay)
}
