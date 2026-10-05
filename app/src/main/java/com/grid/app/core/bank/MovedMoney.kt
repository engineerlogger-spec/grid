package com.grid.app.core.bank

import com.grid.app.core.time.BudgetPeriod
import com.grid.app.core.time.BudgetPeriods
import java.time.LocalDate

/** Money moved between the user's own accounts (e.g. from the salary bank into Revolut). */
data class OwnTransfer(
    val id: Long,
    val date: LocalDate,
    val amountMinor: Long,
    /** True for money arriving on Revolut; false for money sent back. */
    val incoming: Boolean,
    /** A date in the period the user chose to count it in; null = the period of [date]. */
    val countIn: LocalDate?,
    val counterparty: String?,
)

/**
 * "How much did I take from my salary this month": transfers into Revolut minus transfers back, each counted in
 * the period of its date unless the user moved it (a salary transferred on the 29th meant for next month).
 */
object MovedMoney {
    fun periodOf(transfer: OwnTransfer, startDay: Int): BudgetPeriod = BudgetPeriods.periodFor(transfer.countIn ?: transfer.date, startDay)

    fun moved(transfers: List<OwnTransfer>, period: BudgetPeriod, startDay: Int): Long =
        transfers.filter { periodOf(it, startDay) == period }.sumOf { if (it.incoming) it.amountMinor else -it.amountMinor }

    /** How many days before a month starts a transfer is offered to count in that month (a salary moved on the 29th…). */
    const val LATE_DAYS = 7L

    /**
     * What a month's list shows, newest first: transfers counted in it, those dated in it but counted next month,
     * and the last week of the previous month (offered to count here).
     */
    fun shownIn(transfers: List<OwnTransfer>, period: BudgetPeriod, startDay: Int): List<OwnTransfer> =
        transfers.filter {
            periodOf(it, startDay) == period || it.date in period ||
                (it.date < period.start && it.date >= period.start.minusDays(LATE_DAYS))
        }.sortedByDescending { it.date }
}
