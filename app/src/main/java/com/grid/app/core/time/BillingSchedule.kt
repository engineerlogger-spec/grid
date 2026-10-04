package com.grid.app.core.time

import com.grid.app.core.model.Cycle
import com.grid.app.core.model.CycleUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Recurring charge dates. Every date is computed from the anchor (charge #0) rather than by
 * repeatedly adding a month, so a subscription that bills on the 31st returns to the 31st after
 * a short month instead of drifting to the 28th forever.
 */
object BillingSchedule {

    fun chargeDate(anchor: LocalDate, cycle: Cycle, n: Int): LocalDate {
        val steps = n.toLong() * cycle.count
        return when (cycle.unit) {
            CycleUnit.WEEK -> anchor.plusWeeks(steps)
            CycleUnit.MONTH -> anchor.plusMonths(steps)
            CycleUnit.YEAR -> anchor.plusYears(steps)
        }
    }

    /** First charge on or after [date]. */
    fun nextOnOrAfter(anchor: LocalDate, cycle: Cycle, date: LocalDate): LocalDate = chargeDate(anchor, cycle, indexOnOrAfter(anchor, cycle, date))

    /** First charge strictly after [date]. */
    fun nextAfter(anchor: LocalDate, cycle: Cycle, date: LocalDate): LocalDate = nextOnOrAfter(anchor, cycle, date.plusDays(1))

    /** Every charge date in [from, through], in order (used to catch up on missed days). */
    fun chargesThrough(anchor: LocalDate, cycle: Cycle, from: LocalDate, through: LocalDate): List<LocalDate> {
        if (from.isAfter(through)) return emptyList()
        val result = mutableListOf<LocalDate>()
        var n = indexOnOrAfter(anchor, cycle, from)
        while (true) {
            val date = chargeDate(anchor, cycle, n)
            if (date.isAfter(through)) break
            result += date
            n++
        }
        return result
    }

    /** What the subscription costs per month on average (weekly × 52 / 12, yearly / 12, …). */
    fun monthlyEquivalent(amountMinor: Long, cycle: Cycle): Long {
        val perMonth = when (cycle.unit) {
            CycleUnit.WEEK -> BigDecimal(amountMinor).multiply(BigDecimal(52)).divide(BigDecimal(12L * cycle.count), 6, RoundingMode.HALF_UP)
            CycleUnit.MONTH -> BigDecimal(amountMinor).divide(BigDecimal(cycle.count), 6, RoundingMode.HALF_UP)
            CycleUnit.YEAR -> BigDecimal(amountMinor).divide(BigDecimal(12L * cycle.count), 6, RoundingMode.HALF_UP)
        }
        return perMonth.setScale(0, RoundingMode.HALF_UP).toLong()
    }

    fun yearly(amountMinor: Long, cycle: Cycle): Long {
        val perYear = when (cycle.unit) {
            CycleUnit.WEEK -> BigDecimal(amountMinor).multiply(BigDecimal(52)).divide(BigDecimal(cycle.count), 6, RoundingMode.HALF_UP)
            CycleUnit.MONTH -> BigDecimal(amountMinor).multiply(BigDecimal(12)).divide(BigDecimal(cycle.count), 6, RoundingMode.HALF_UP)
            CycleUnit.YEAR -> BigDecimal(amountMinor).divide(BigDecimal(cycle.count), 6, RoundingMode.HALF_UP)
        }
        return perYear.setScale(0, RoundingMode.HALF_UP).toLong()
    }

    private fun indexOnOrAfter(anchor: LocalDate, cycle: Cycle, date: LocalDate): Int {
        if (!date.isAfter(anchor)) return 0
        val unit = when (cycle.unit) {
            CycleUnit.WEEK -> ChronoUnit.WEEKS
            CycleUnit.MONTH -> ChronoUnit.MONTHS
            CycleUnit.YEAR -> ChronoUnit.YEARS
        }
        // Start from an estimate, then correct: month lengths make the estimate off by at most one.
        var n = (unit.between(anchor, date) / cycle.count).toInt().coerceAtLeast(0)
        while (n > 0 && !chargeDate(anchor, cycle, n - 1).isBefore(date)) n--
        while (chargeDate(anchor, cycle, n).isBefore(date)) n++
        return n
    }
}
