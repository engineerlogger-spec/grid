package com.grid.app.core.insights

import com.grid.app.core.model.TxType
import com.grid.app.core.time.BudgetPeriod
import java.time.LocalDate
import java.util.Locale

/** Minimal transaction view for analysis. [title] is what lists show (merchant, else note, else category). */
data class InsightEntry(
    val type: TxType,
    val amountMinor: Long,
    val categoryId: Long,
    val methodId: Long?,
    val title: String,
    val date: LocalDate,
    val isSubscription: Boolean,
    /**
     * A scheduled, one-per-period payment (subscription charge, settled bill such as rent).
     * Counted once in projections instead of being extrapolated like day-to-day spending.
     */
    val isFixed: Boolean = isSubscription,
)

data class Kpis(val spentMinor: Long, val incomeMinor: Long, val savedMinor: Long, val dailyAverageMinor: Long, val elapsedDays: Int)

data class Share(val id: Long, val amountMinor: Long, val fraction: Float)

data class MethodShare(val methodId: Long?, val amountMinor: Long, val fraction: Float)

data class MerchantTotal(val title: String, val amountMinor: Long, val count: Int)

data class PeriodTotals(val period: BudgetPeriod, val spentMinor: Long, val incomeMinor: Long)

/** Cumulative spending per elapsed day, the straight budget line, and the end-of-period projection. */
data class Pace(val cumulative: List<Long>, val length: Int, val goalMinor: Long?, val projectedMinor: Long)

data class CategoryBudget(val categoryId: Long, val limitMinor: Long, val spentMinor: Long) {
    val fraction: Float get() = if (limitMinor <= 0) 0f else spentMinor.toFloat() / limitMinor
}

/** Generated observations, rendered as sentences by the UI. */
sealed interface Insight {
    data class CategoryIncrease(val categoryId: Long, val deltaMinor: Long, val fraction: Float) : Insight
    data class ProjectionOver(val projectedMinor: Long, val goalMinor: Long) : Insight
    data class ProjectionUnder(val projectedMinor: Long, val goalMinor: Long) : Insight
    data class HighestDay(val date: LocalDate, val amountMinor: Long) : Insight
    data class SubscriptionsShare(val fraction: Float, val amountMinor: Long) : Insight
    data class SavingRate(val fraction: Float) : Insight
}

data class InsightsReport(
    val kpis: Kpis,
    val byCategory: List<Share>,
    val byMethod: List<MethodShare>,
    val topMerchants: List<MerchantTotal>,
    val history: List<PeriodTotals>,
    val pace: Pace,
    val insights: List<Insight>,
    val budgets: List<CategoryBudget>,
    val isCurrent: Boolean,
)

object InsightsEngine {
    private const val MIN_INCREASE_MINOR = 1000L       // ignore changes below 10 units of currency
    private const val MIN_INCREASE_FRACTION = 0.20f
    private const val MIN_DAYS_FOR_PROJECTION = 5

    fun compute(
        period: BudgetPeriod,
        today: LocalDate,
        goalMinor: Long?,
        entries: List<InsightEntry>,
        history: List<BudgetPeriod>,
        categoryLimits: Map<Long, Long>,
    ): InsightsReport {
        val inPeriod = entries.filter { it.date in period }
        val expenses = inPeriod.filter { it.type == TxType.EXPENSE }
        val spent = expenses.sumOf { it.amountMinor }
        val income = inPeriod.filter { it.type == TxType.INCOME }.sumOf { it.amountMinor }
        val isCurrent = today in period
        val elapsed = when {
            isCurrent -> period.dayNumber(today)
            today.isBefore(period.start) -> 0
            else -> period.length
        }

        val byCategory = shares(expenses.groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { it.amountMinor } }, spent)
        val byMethod = expenses.groupBy { it.methodId }
            .map { (id, l) -> MethodShare(id, l.sumOf { it.amountMinor }, fraction(l.sumOf { it.amountMinor }, spent)) }
            .sortedByDescending { it.amountMinor }
        val merchants = expenses.groupBy { it.title.trim().lowercase(Locale.ROOT) }
            .map { (_, l) -> MerchantTotal(l.first().title.trim(), l.sumOf { it.amountMinor }, l.size) }
            .sortedWith(compareByDescending<MerchantTotal> { it.amountMinor }.thenByDescending { it.count })
            .take(5)

        val spentByDay = expenses.groupBy { it.date }.mapValues { (_, l) -> l.sumOf { it.amountMinor } }
        var running = 0L
        val cumulative = (0 until elapsed).map { i -> running += spentByDay[period.start.plusDays(i.toLong())] ?: 0L; running }
        // Fixed payments happen once a period: extrapolating the €850 rent paid on day 4 would
        // "project" €6,600. Only day-to-day spending runs at a daily rate.
        val fixed = expenses.filter { it.isFixed }.sumOf { it.amountMinor }
        val projected = if (isCurrent && elapsed > 0) fixed + (spent - fixed) * period.length / elapsed else spent
        val pace = Pace(cumulative, period.length, goalMinor, projected)

        val totals = history.sortedBy { it.start }.map { p ->
            val inP = entries.filter { it.date in p }
            PeriodTotals(p, inP.filter { it.type == TxType.EXPENSE }.sumOf { it.amountMinor }, inP.filter { it.type == TxType.INCOME }.sumOf { it.amountMinor })
        }

        val insights = buildList {
            categoryIncrease(period, elapsed, expenses, entries, history)?.let(::add)
            if (isCurrent && goalMinor != null && elapsed >= MIN_DAYS_FOR_PROJECTION && spent > 0) {
                add(if (projected > goalMinor) Insight.ProjectionOver(projected, goalMinor) else Insight.ProjectionUnder(projected, goalMinor))
            }
            spentByDay.maxByOrNull { it.value }?.takeIf { it.value > 0 && spentByDay.size > 1 }?.let { add(Insight.HighestDay(it.key, it.value)) }
            val subs = expenses.filter { it.isSubscription }.sumOf { it.amountMinor }
            if (subs > 0 && spent > 0) add(Insight.SubscriptionsShare(subs.toFloat() / spent, subs))
            if (income > 0 && !isCurrent) add(Insight.SavingRate((income - spent).toFloat() / income))
        }

        val budgets = categoryLimits.filterValues { it > 0 }.map { (id, limit) ->
            CategoryBudget(id, limit, expenses.filter { it.categoryId == id }.sumOf { it.amountMinor })
        }.sortedByDescending { it.fraction }

        return InsightsReport(
            kpis = Kpis(spent, income, income - spent, if (elapsed > 0) spent / elapsed else 0, elapsed),
            byCategory = byCategory,
            byMethod = byMethod,
            topMerchants = merchants,
            history = totals,
            pace = pace,
            insights = insights,
            budgets = budgets,
            isCurrent = isCurrent,
        )
    }

    /**
     * Biggest category increase vs the previous period. For a period in progress the comparison is
     * "so far this month vs the same days last month", otherwise every month looks cheaper until it ends.
     */
    private fun categoryIncrease(
        period: BudgetPeriod,
        elapsed: Int,
        expenses: List<InsightEntry>,
        all: List<InsightEntry>,
        history: List<BudgetPeriod>,
    ): Insight.CategoryIncrease? {
        val previous = history.filter { it.endExclusive <= period.start }.maxByOrNull { it.start } ?: return null
        val previousCutoff = previous.start.plusDays(elapsed.toLong())
        val before = all.filter { it.type == TxType.EXPENSE && it.date in previous && it.date.isBefore(previousCutoff) }
            .groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { it.amountMinor } }
        val now = expenses.groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { it.amountMinor } }
        return now.mapNotNull { (id, amount) ->
            val prev = before[id] ?: 0L
            val delta = amount - prev
            val grew = if (prev == 0L) 1f else delta.toFloat() / prev
            if (delta >= MIN_INCREASE_MINOR && grew >= MIN_INCREASE_FRACTION) Insight.CategoryIncrease(id, delta, grew) else null
        }.maxByOrNull { it.deltaMinor }
    }

    private fun shares(amounts: Map<Long, Long>, total: Long): List<Share> =
        amounts.map { (id, amount) -> Share(id, amount, fraction(amount, total)) }.sortedByDescending { it.amountMinor }

    private fun fraction(part: Long, total: Long): Float = if (total == 0L) 0f else part.toFloat() / total
}
