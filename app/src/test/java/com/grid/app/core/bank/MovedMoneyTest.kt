package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.time.BudgetPeriods
import org.junit.Test
import java.time.LocalDate

class MovedMoneyTest {

    private fun d(s: String) = LocalDate.parse(s)
    private fun t(id: Long, date: String, amount: Long, incoming: Boolean = true, countIn: String? = null) =
        OwnTransfer(id, d(date), amount, incoming, countIn?.let(::d), "Me")

    private val october = BudgetPeriods.periodFor(d("2026-10-10"), 1)
    private val september = BudgetPeriods.periodFor(d("2026-09-10"), 1)

    @Test fun eachTransferCountsInTheMonthOfItsDate() {
        val transfers = listOf(t(1, "2026-09-29", 200_000), t(2, "2026-10-15", 30_000))
        assertThat(MovedMoney.moved(transfers, september, 1)).isEqualTo(200_000)
        assertThat(MovedMoney.moved(transfers, october, 1)).isEqualTo(30_000)
    }

    @Test fun aTransferMovedToNextMonthCountsThere() {
        val transfers = listOf(t(1, "2026-09-29", 200_000, countIn = "2026-10-01"), t(2, "2026-10-15", 30_000))
        assertThat(MovedMoney.moved(transfers, september, 1)).isEqualTo(0)
        assertThat(MovedMoney.moved(transfers, october, 1)).isEqualTo(230_000)
    }

    @Test fun moneySentBackIsSubtracted() {
        val transfers = listOf(t(1, "2026-10-02", 200_000), t(2, "2026-10-20", 20_000, incoming = false))
        assertThat(MovedMoney.moved(transfers, october, 1)).isEqualTo(180_000)
    }

    @Test fun followsCustomPeriodStarts() {
        // Periods starting on the 25th: 2026-09-29 falls in the period starting 2026-09-25.
        val period = BudgetPeriods.periodFor(d("2026-09-29"), 25)
        assertThat(MovedMoney.moved(listOf(t(1, "2026-09-29", 100)), period, 25)).isEqualTo(100)
    }

    @Test fun listsWhatBelongsToOrComesFromAMonth() {
        val transfers = listOf(
            t(1, "2026-09-29", 1, countIn = "2026-10-01"), // moved into October
            t(2, "2026-10-05", 1),
            t(3, "2026-10-30", 1, countIn = "2026-11-01"), // dated October, moved to November
            t(4, "2026-09-10", 1), // well inside September: not offered
            t(5, "2026-09-30", 1), // last week of September: offered to count in October
        )
        assertThat(MovedMoney.shownIn(transfers, october, 1).map { it.id }).containsExactly(3L, 2L, 5L, 1L).inOrder()
    }
}
