package com.grid.app.core.data.repo

import androidx.room.withTransaction
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.IncomeSourceEntity
import com.grid.app.core.data.db.entities.PeriodPlanEntity
import com.grid.app.core.data.db.entities.TransactionEntity
import com.grid.app.core.model.IncomeLine
import com.grid.app.core.model.IncomeSource
import com.grid.app.core.model.PeriodPlan
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exact plan for the period if any; otherwise the most recent plan starting before the period
 * ends. ("Before it ends", not "before it starts": moving the pay day earlier creates a period
 * that starts before the current plan, and the goal must still carry over.)
 */
internal fun carriedGoal(plansNewestFirst: List<PeriodPlanEntity>, period: BudgetPeriod): Long? {
    val start = period.start.toEpochDay()
    val end = period.endExclusive.toEpochDay()
    return (plansNewestFirst.firstOrNull { it.periodStartEpochDay == start } ?: plansNewestFirst.firstOrNull { it.periodStartEpochDay < end })?.goalMinor
}

/** Spending goals per budget period and the monthly income check-in. */
@Singleton
class PlanRepository @Inject constructor(
    private val db: GridDatabase,
    private val clock: AppClock,
    private val listeners: Set<@JvmSuppressWildcards LedgerListener>,
) {
    private val dao = db.planDao()

    fun observePlan(period: BudgetPeriod): Flow<PeriodPlan?> =
        dao.observePlan(period.start.toEpochDay()).map { it?.toDomain() }

    /** The period's goal, or the goal carried over from the latest plan that began before this period ends. */
    fun observeGoal(period: BudgetPeriod): Flow<Long?> =
        dao.observeAllPlans().map { plans -> carriedGoal(plans, period) }.distinctUntilChanged()

    fun observeNeedsCheckIn(period: BudgetPeriod): Flow<Boolean> =
        dao.observePlan(period.start.toEpochDay()).map { it?.incomeConfirmedAt == null }.distinctUntilChanged()

    fun observeIncomeSources(): Flow<List<IncomeSource>> = dao.observeIncomeSources().map { list -> list.map { it.toDomain() } }

    suspend fun incomeSources(): List<IncomeSource> = dao.incomeSources().map { it.toDomain() }

    suspend fun goalFor(period: BudgetPeriod): Long? = carriedGoal(dao.observeAllPlans().first(), period)


    suspend fun setGoal(period: BudgetPeriod, goalMinor: Long) {
        val start = period.start.toEpochDay()
        val existing = dao.getPlan(start)
        dao.upsertPlan(existing?.copy(goalMinor = goalMinor) ?: PeriodPlanEntity(start, goalMinor, null))
        listeners.notifyAll()
    }

    /**
     * Books this period's confirmed income as INCOME transactions, remembers the lines as templates
     * for next month, and stores the goal — atomically.
     */
    suspend fun confirmCheckIn(period: BudgetPeriod, currency: String, goalMinor: Long, lines: List<IncomeLine>) {
        val now = clock.millis()
        db.withTransaction {
            val txDao = db.transactionDao()
            // Periods can overlap when the pay day changes; never book the same income twice in one period.
            val alreadyBooked = txDao.between(period.startMillis(clock.zone), period.endMillis(clock.zone))
                .filter { it.source == TxSource.CHECKIN && it.type == TxType.INCOME }
                .mapNotNull { it.note?.trim()?.lowercase() }
                .toSet()
            lines.filter { it.active && it.amountMinor > 0 && it.name.trim().lowercase() !in alreadyBooked }.forEach { line ->
                txDao.insert(
                    TransactionEntity(
                        type = TxType.INCOME, amountMinor = line.amountMinor, currency = currency, categoryId = line.categoryId,
                        note = line.name.trim().ifBlank { null }, occurredAt = now, createdAt = now, updatedAt = now, source = TxSource.CHECKIN,
                    ),
                )
            }
            dao.replaceIncomeSources(
                lines.filter { it.name.isNotBlank() }.mapIndexed { index, line ->
                    IncomeSourceEntity(name = line.name.trim(), amountMinor = line.amountMinor, categoryId = line.categoryId, position = index, active = line.active)
                },
            )
            dao.upsertPlan(PeriodPlanEntity(period.start.toEpochDay(), goalMinor, now))
        }
        listeners.notifyAll()
    }
}
