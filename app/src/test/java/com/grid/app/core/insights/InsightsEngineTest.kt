package com.grid.app.core.insights

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.model.TxType.EXPENSE
import com.grid.app.core.model.TxType.INCOME
import com.grid.app.core.time.BudgetPeriod
import com.grid.app.core.time.BudgetPeriods
import org.junit.Test
import java.time.LocalDate

class InsightsEngineTest {

    private fun d(s: String) = LocalDate.parse(s)
    private val today = d("2026-10-10")
    private val october = BudgetPeriods.periodFor(today, 1)
    private val history: List<BudgetPeriod> = (5 downTo 0).map { back -> BudgetPeriods.periodFor(today.minusMonths(back.toLong()), 1) }

    private fun e(amount: Long, cat: Long, date: String, title: String = "x", method: Long? = null, sub: Boolean = false, type: com.grid.app.core.model.TxType = EXPENSE) =
        InsightEntry(type, amount, cat, method, title, d(date), isSubscription = sub, isFixed = sub)

    private val entries = listOf(
        e(250000, 100, "2026-10-01", "Salary", type = INCOME),
        e(4000, 1, "2026-10-02", "Starbucks", method = 3),
        e(6000, 1, "2026-10-03", "starbucks ", method = 3),
        e(10000, 2, "2026-10-05", "Market", method = 1),
        e(1399, 7, "2026-10-04", "Netflix", sub = true),
        e(20000, 2, "2026-10-09", "Market", method = 1),
        // September: groceries 5000 by day 10, restaurants 2000
        e(5000, 2, "2026-09-03", "Market"),
        e(2000, 1, "2026-09-08", "Cafe"),
        e(40000, 2, "2026-09-25", "Market"), // after day 10 → excluded from "same point" comparison
        e(300000, 100, "2026-09-01", "Salary", type = INCOME),
        e(9999, 2, "2026-04-15", "Market"), // inside the 6-period window
    )

    private fun report(goal: Long? = 200000, period: BudgetPeriod = october) =
        InsightsEngine.compute(period, today, goal, entries, history, categoryLimits = mapOf(2L to 25000L))

    @Test fun kpisForCurrentPeriod() {
        val k = report().kpis
        assertThat(k.spentMinor).isEqualTo(41399)
        assertThat(k.incomeMinor).isEqualTo(250000)
        assertThat(k.savedMinor).isEqualTo(250000 - 41399)
        assertThat(k.elapsedDays).isEqualTo(10)
        assertThat(k.dailyAverageMinor).isEqualTo(4139)
    }

    @Test fun pastPeriodUsesFullLength() {
        val september = BudgetPeriods.previous(october, 1)
        val k = report(period = september).kpis
        assertThat(k.elapsedDays).isEqualTo(30)
        assertThat(k.dailyAverageMinor).isEqualTo(47000 / 30)
    }

    @Test fun categoriesSortedWithFractions() {
        val cats = report().byCategory
        assertThat(cats.map { it.id }).containsExactly(2L, 1L, 7L).inOrder()
        assertThat(cats.first().amountMinor).isEqualTo(30000)
        assertThat(cats.sumOf { it.fraction.toDouble() }).isWithin(0.001).of(1.0)
    }

    @Test fun paceCumulativeAndProjection() {
        val pace = report().pace
        assertThat(pace.cumulative).hasSize(10)
        assertThat(pace.cumulative[0]).isEqualTo(0)
        assertThat(pace.cumulative[1]).isEqualTo(4000)
        assertThat(pace.cumulative.last()).isEqualTo(41399)
        // Fixed payments (the Netflix charge) count once; only day-to-day spending is extrapolated.
        assertThat(pace.projectedMinor).isEqualTo(1399 + 40000L * 31 / 10)
        assertThat(pace.goalMinor).isEqualTo(200000)
    }

    @Test fun historyHasSixPeriodsOldestFirst() {
        val h = report().history
        assertThat(h).hasSize(6)
        assertThat(h.first().period.start).isEqualTo(d("2026-05-01"))
        assertThat(h.last().spentMinor).isEqualTo(41399)
        assertThat(h[4].spentMinor).isEqualTo(47000)
        assertThat(h[4].incomeMinor).isEqualTo(300000)
    }

    @Test fun methodsAndMerchants() {
        val r = report()
        assertThat(r.byMethod.first().methodId).isEqualTo(1L)
        assertThat(r.byMethod.first().amountMinor).isEqualTo(30000)
        val starbucks = r.topMerchants.first { it.title.equals("Starbucks", ignoreCase = true) }
        assertThat(starbucks.count).isEqualTo(2)
        assertThat(starbucks.amountMinor).isEqualTo(10000)
        assertThat(r.topMerchants.first().title).isEqualTo("Market")
    }

    @Test fun categoryIncreaseComparesSamePointLastPeriod() {
        val increase = report().insights.filterIsInstance<Insight.CategoryIncrease>().single()
        // Groceries: 30000 now vs 5000 by day 10 of September.
        assertThat(increase.categoryId).isEqualTo(2L)
        assertThat(increase.deltaMinor).isEqualTo(25000)
    }

    @Test fun projectionAndSubscriptionsInsights() {
        val insights = report(goal = 100000).insights
        assertThat(insights.filterIsInstance<Insight.ProjectionOver>().single().projectedMinor).isEqualTo(1399 + 40000L * 31 / 10)
        assertThat(insights.filterIsInstance<Insight.SubscriptionsShare>().single().fraction).isWithin(0.001f).of(1399f / 41399f)
        assertThat(insights.filterIsInstance<Insight.HighestDay>().single().date).isEqualTo(d("2026-10-09"))
    }

    @Test fun budgetsProgress() {
        val budget = report().budgets.single()
        assertThat(budget.categoryId).isEqualTo(2L)
        assertThat(budget.spentMinor).isEqualTo(30000)
        assertThat(budget.fraction).isWithin(0.001f).of(1.2f)
    }

    @Test fun oneOffBigBillIsNotExtrapolated() {
        val rentDay4 = listOf(e(85000, 9, "2026-10-04", "Rent").copy(isFixed = true), e(4000, 1, "2026-10-02", "Lunch"))
        val pace = InsightsEngine.compute(october, d("2026-10-04"), 200000, rentDay4, history, emptyMap()).pace
        assertThat(pace.projectedMinor).isEqualTo(85000 + 4000L * 31 / 4)
    }

    @Test fun emptyPeriodIsQuiet() {
        val r = InsightsEngine.compute(october, today, null, emptyList(), history, emptyMap())
        assertThat(r.kpis.spentMinor).isEqualTo(0)
        assertThat(r.byCategory).isEmpty()
        assertThat(r.insights).isEmpty()
        assertThat(r.pace.projectedMinor).isEqualTo(0)
    }
}
