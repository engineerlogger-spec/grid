package com.grid.app.core.insights

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.model.TxType.EXPENSE
import com.grid.app.core.model.TxType.INCOME
import com.grid.app.core.time.BudgetPeriods
import org.junit.Test
import java.time.LocalDate

class DashboardCalculatorTest {

    private fun d(s: String) = LocalDate.parse(s)
    private val october = BudgetPeriods.periodFor(d("2026-10-14"), 1)
    private val today = d("2026-10-14")

    private val entries = listOf(
        LedgerEntry(INCOME, 320000, categoryId = 100, date = d("2026-10-01")),
        LedgerEntry(EXPENSE, 10000, categoryId = 1, date = d("2026-10-02")),   // day ratio 1.0 (allowance 10000)
        LedgerEntry(EXPENSE, 5000, categoryId = 2, date = d("2026-10-03")),
        LedgerEntry(EXPENSE, 25000, categoryId = 1, date = d("2026-10-05")),   // over
        LedgerEntry(EXPENSE, 3000, categoryId = 3, date = d("2026-10-14")),    // today
        LedgerEntry(EXPENSE, 999999, categoryId = 1, date = d("2026-09-30")),  // outside period
    )

    private fun summary(goal: Long? = 310000) = DashboardCalculator.summarize(october, today, goal, entries)

    @Test fun totalsIgnoreEntriesOutsideThePeriod() {
        val s = summary()
        assertThat(s.spentMinor).isEqualTo(43000)
        assertThat(s.incomeMinor).isEqualTo(320000)
    }

    @Test fun leftAndPerDay() {
        val s = summary()
        assertThat(s.leftMinor).isEqualTo(267000)
        assertThat(s.daysLeft).isEqualTo(18)
        assertThat(s.perDayMinor).isEqualTo(267000 / 18)
        assertThat(s.dayNumber).isEqualTo(14)
        assertThat(s.length).isEqualTo(31)
    }

    @Test fun overspentHasZeroPerDay() {
        val s = summary(goal = 40000)
        assertThat(s.leftMinor).isEqualTo(-3000)
        assertThat(s.perDayMinor).isEqualTo(0)
    }

    @Test fun noGoalMeansNoLeftAndZeroRatios() {
        val s = summary(goal = null)
        assertThat(s.leftMinor).isNull()
        assertThat(s.perDayMinor).isNull()
        assertThat(s.days.map { it.ratio }.toSet()).containsExactly(0f)
    }

    @Test fun dayCellsCarryStateAndRatio() {
        val days = summary().days
        assertThat(days).hasSize(31)
        assertThat(days[1].ratio).isWithin(0.001f).of(1f)        // Oct 2: 10000 / (310000/31)
        assertThat(days[4].ratio).isWithin(0.001f).of(2.5f)      // Oct 5
        assertThat(days[4].spentMinor).isEqualTo(25000)
        assertThat(days[13].state).isEqualTo(DayState.TODAY)
        assertThat(days[12].state).isEqualTo(DayState.PAST)
        assertThat(days[14].state).isEqualTo(DayState.FUTURE)
    }

    @Test fun topCategoriesSortedWithFractionsOfAllSpending() {
        val top = summary().topCategories
        assertThat(top.map { it.categoryId }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(top[0].amountMinor).isEqualTo(35000)
        assertThat(top.sumOf { it.fraction.toDouble() }).isWithin(0.001).of(1.0)
    }

    @Test fun topCategoriesLimitedToN() {
        val s = DashboardCalculator.summarize(october, today, 310000, entries, topN = 2)
        assertThat(s.topCategories).hasSize(2)
        assertThat(s.topCategories[1].fraction).isWithin(0.001f).of(5000f / 43000f)
    }

    @Test fun emptyLedger() {
        val s = DashboardCalculator.summarize(october, today, 310000, emptyList())
        assertThat(s.spentMinor).isEqualTo(0)
        assertThat(s.leftMinor).isEqualTo(310000)
        assertThat(s.topCategories).isEmpty()
    }
}
