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
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

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

    /** The period's goal, or the most recent earlier goal when this period has none yet. */
    fun observeGoal(period: BudgetPeriod): Flow<Long?> {
        val start = period.start.toEpochDay()
        return dao.observeAllPlans().map { plans ->
            (plans.firstOrNull { it.periodStartEpochDay == start } ?: plans.firstOrNull { it.periodStartEpochDay < start })?.goalMinor
        }.distinctUntilChanged()
    }

    fun observeNeedsCheckIn(period: BudgetPeriod): Flow<Boolean> =
        dao.observePlan(period.start.toEpochDay()).map { it?.incomeConfirmedAt == null }.distinctUntilChanged()

    fun observeIncomeSources(): Flow<List<IncomeSource>> = dao.observeIncomeSources().map { list -> list.map { it.toDomain() } }

    suspend fun incomeSources(): List<IncomeSource> = dao.incomeSources().map { it.toDomain() }

    suspend fun goalFor(period: BudgetPeriod): Long? {
        val start = period.start.toEpochDay()
        return (dao.getPlan(start) ?: dao.latestBefore(start))?.goalMinor
    }

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
            lines.filter { it.active && it.amountMinor > 0 }.forEach { line ->
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
