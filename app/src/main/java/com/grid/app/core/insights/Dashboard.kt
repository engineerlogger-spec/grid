package com.grid.app.core.insights

import com.grid.app.core.model.TxType
import com.grid.app.core.time.BudgetPeriod
import java.time.LocalDate

/** Minimal view of a transaction for calculations — keeps the math independent of Room/UI types. */
data class LedgerEntry(val type: TxType, val amountMinor: Long, val categoryId: Long, val date: LocalDate)

enum class DayState { PAST, TODAY, FUTURE }

/** One cell of the month grid. [ratio] = day's spending ÷ daily allowance (0 when there's no goal). */
data class DayCell(val date: LocalDate, val state: DayState, val spentMinor: Long, val ratio: Float)

data class CategoryShare(val categoryId: Long, val amountMinor: Long, val fraction: Float)

data class DashboardSummary(
    val spentMinor: Long,
    val incomeMinor: Long,
    val goalMinor: Long?,
    /** goal − spent; negative when over budget; null without a goal. */
    val leftMinor: Long?,
    /** What can be spent per remaining day (incl. today) to finish on budget; 0 when over. */
    val perDayMinor: Long?,
    val days: List<DayCell>,
    val topCategories: List<CategoryShare>,
    val dayNumber: Int,
    val daysLeft: Int,
    val length: Int,
)

object DashboardCalculator {

    fun summarize(
        period: BudgetPeriod,
        today: LocalDate,
        goalMinor: Long?,
        entries: List<LedgerEntry>,
        topN: Int = 3,
    ): DashboardSummary {
        val inPeriod = entries.filter { it.date in period }
        val expenses = inPeriod.filter { it.type == TxType.EXPENSE }
        val spent = expenses.sumOf { it.amountMinor }
        val income = inPeriod.filter { it.type == TxType.INCOME }.sumOf { it.amountMinor }
        val daysLeft = period.daysLeft(today)

        val left = goalMinor?.let { it - spent }
        val perDay = left?.let { if (it <= 0 || daysLeft == 0) 0L else it / daysLeft }

        val allowance = goalMinor?.takeIf { it > 0 }?.toFloat()?.div(period.length)
        val spentByDay = expenses.groupBy { it.date }.mapValues { (_, list) -> list.sumOf { it.amountMinor } }
        val days = period.days().map { date ->
            val daySpent = spentByDay[date] ?: 0L
            DayCell(
                date = date,
                state = when {
                    date == today -> DayState.TODAY
                    date.isBefore(today) -> DayState.PAST
                    else -> DayState.FUTURE
                },
                spentMinor = daySpent,
                ratio = if (allowance == null) 0f else daySpent / allowance,
            )
        }

        val top = expenses.groupBy { it.categoryId }
            .map { (id, list) -> id to list.sumOf { it.amountMinor } }
            .sortedByDescending { it.second }
            .take(topN)
            .map { (id, amount) -> CategoryShare(id, amount, if (spent == 0L) 0f else amount.toFloat() / spent) }

        return DashboardSummary(
            spentMinor = spent,
            incomeMinor = income,
            goalMinor = goalMinor,
            leftMinor = left,
            perDayMinor = perDay,
            days = days,
            topCategories = top,
            dayNumber = period.dayNumber(today).coerceIn(1, period.length),
            daysLeft = daysLeft,
            length = period.length,
        )
    }
}
