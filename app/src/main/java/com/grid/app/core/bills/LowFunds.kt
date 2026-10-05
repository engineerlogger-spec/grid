package com.grid.app.core.bills

import com.grid.app.core.model.PendingDirection
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Money that will be missing before the end of the month, and when it runs out. */
data class LowFundsAlert(
    /** How much more is needed to cover everything up to the end of the month. */
    val shortMinor: Long,
    /** The first day the money runs out. */
    val by: LocalDate,
    /** The payment that first doesn't fit; null when everyday spending alone runs it out. */
    val firstUncovered: UpcomingItem?,
    val availableMinor: Long,
    /** All the bills still to pay up to the end of the month. */
    val dueMinor: Long,
)

/**
 * Walks day by day from today to [until]: the money available now, minus each bill on its day, minus the usual daily
 * spending (0 to count bills only). Warns when it would go below zero.
 */
object LowFunds {
    fun check(availableMinor: Long, bills: List<UpcomingItem>, dailySpendMinor: Long, today: LocalDate, until: LocalDate): LowFundsAlert? {
        val due = bills.filter { it.direction == PendingDirection.I_OWE && !it.date.isAfter(until) }
            .map { if (it.date.isBefore(today)) it.copy(date = today) else it } // late bills are still to pay
        val byDay = due.groupBy { it.date }
        var paid = 0L
        var lowest = Long.MAX_VALUE
        var runsOut: LocalDate? = null
        var first: UpcomingItem? = null
        var day = today
        while (!day.isAfter(until)) {
            val todays = byDay[day].orEmpty().sortedByDescending { it.amountMinor }
            paid += todays.sumOf { it.amountMinor }
            val left = availableMinor - paid - dailySpendMinor * ChronoUnit.DAYS.between(today, day)
            lowest = minOf(lowest, left)
            if (left < 0 && runsOut == null) {
                runsOut = day
                first = todays.firstOrNull()
            }
            day = day.plusDays(1)
        }
        val date = runsOut ?: return null
        return LowFundsAlert(-lowest, date, first, availableMinor, due.sumOf { it.amountMinor })
    }
}
