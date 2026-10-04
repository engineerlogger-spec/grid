package com.grid.app.core.data.repo

import androidx.room.withTransaction
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.SubscriptionEntity
import com.grid.app.core.data.db.entities.TransactionEntity
import com.grid.app.core.model.Category
import com.grid.app.core.model.Cycle
import com.grid.app.core.model.Subscription
import com.grid.app.core.model.SubscriptionDraft
import com.grid.app.core.model.SubscriptionStatus
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BillingSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepository @Inject constructor(
    private val db: GridDatabase,
    private val clock: AppClock,
    private val listeners: Set<@JvmSuppressWildcards LedgerListener>,
) {
    private val dao = db.subscriptionDao()
    private val categories: Flow<Map<Long, Category>> =
        db.categoryDao().observeAll().map { list -> list.associate { it.id to it.toDomain() } }

    fun observeAll(): Flow<List<Subscription>> =
        combine(dao.observeAll(), categories) { rows, cats -> rows.mapNotNull { it.toDomain(cats) } }

    suspend fun get(id: Long): Subscription? = dao.get(id)?.toDomain(categories.first())

    suspend fun all(): List<Subscription> {
        val cats = categories.first()
        return dao.all().mapNotNull { it.toDomain(cats) }
    }

    suspend fun add(draft: SubscriptionDraft): Long {
        val epoch = draft.nextCharge.toEpochDay()
        val id = dao.insert(
            SubscriptionEntity(
                name = draft.name.trim(), amountMinor = draft.amountMinor, currency = draft.currency,
                cycleUnit = draft.cycle.unit, cycleCount = draft.cycle.count,
                anchorEpochDay = epoch, nextChargeEpochDay = epoch,
                categoryId = draft.categoryId, paymentMethodId = draft.paymentMethodId,
                remindDaysBefore = draft.remindDaysBefore, autoLog = draft.autoLog,
                colorKey = draft.colorKey, note = draft.note?.trim()?.ifBlank { null }, createdAt = clock.millis(),
            ),
        )
        processDueCharges(clock.today())
        return id
    }

    /** Re-anchors only when the schedule itself changed, so a month-end anchor survives an amount edit. */
    suspend fun update(id: Long, draft: SubscriptionDraft) {
        val existing = dao.get(id) ?: return
        val scheduleChanged = existing.nextChargeEpochDay != draft.nextCharge.toEpochDay() ||
            existing.cycleUnit != draft.cycle.unit || existing.cycleCount != draft.cycle.count
        val epoch = draft.nextCharge.toEpochDay()
        dao.update(
            existing.copy(
                name = draft.name.trim(), amountMinor = draft.amountMinor, currency = draft.currency,
                cycleUnit = draft.cycle.unit, cycleCount = draft.cycle.count,
                anchorEpochDay = if (scheduleChanged) epoch else existing.anchorEpochDay,
                nextChargeEpochDay = epoch,
                categoryId = draft.categoryId, paymentMethodId = draft.paymentMethodId,
                remindDaysBefore = draft.remindDaysBefore, autoLog = draft.autoLog,
                colorKey = draft.colorKey, note = draft.note?.trim()?.ifBlank { null },
            ),
        )
        processDueCharges(clock.today())
    }

    /** Resuming re-anchors the next charge to today or later: a paused stretch is never back-charged. */
    suspend fun setStatus(id: Long, status: SubscriptionStatus) {
        val existing = dao.get(id) ?: return
        val next = if (status == SubscriptionStatus.ACTIVE && existing.status != SubscriptionStatus.ACTIVE) {
            BillingSchedule.nextOnOrAfter(LocalDate.ofEpochDay(existing.anchorEpochDay), existing.cycle(), clock.today()).toEpochDay()
        } else {
            existing.nextChargeEpochDay
        }
        dao.update(existing.copy(status = status, nextChargeEpochDay = next))
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /**
     * Books every charge due on or before [today] for active, auto-logging subscriptions and moves
     * their next charge forward. Idempotent per subscription and day. Returns how many were booked.
     */
    suspend fun processDueCharges(today: LocalDate): Int {
        var booked = 0
        db.withTransaction {
            val txDao = db.transactionDao()
            for (sub in dao.dueOnOrBefore(today.toEpochDay())) {
                val anchor = LocalDate.ofEpochDay(sub.anchorEpochDay)
                val cycle = sub.cycle()
                if (sub.autoLog) {
                    for (date in BillingSchedule.chargesThrough(anchor, cycle, LocalDate.ofEpochDay(sub.nextChargeEpochDay), today)) {
                        // ±3 days: bank sync may already have booked the real charge a little early or late.
                        val windowStart = date.minusDays(CHARGE_SLACK_DAYS).atStartOfDay(clock.zone).toInstant().toEpochMilli()
                        val windowEnd = date.plusDays(CHARGE_SLACK_DAYS + 1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
                        if (txDao.countForSubscriptionBetween(sub.id, windowStart, windowEnd) > 0) continue
                        val at = if (date == today) clock.millis() else date.atTime(9, 0).atZone(clock.zone).toInstant().toEpochMilli()
                        txDao.insert(
                            TransactionEntity(
                                type = TxType.EXPENSE, amountMinor = sub.amountMinor, currency = sub.currency,
                                categoryId = sub.categoryId, paymentMethodId = sub.paymentMethodId,
                                merchant = sub.name, occurredAt = at, createdAt = clock.millis(), updatedAt = clock.millis(),
                                source = TxSource.SUBSCRIPTION, subscriptionId = sub.id,
                            ),
                        )
                        booked++
                    }
                }
                dao.update(sub.copy(nextChargeEpochDay = BillingSchedule.nextAfter(anchor, cycle, today).toEpochDay()))
            }
        }
        if (booked > 0) listeners.notifyAll()
        return booked
    }

    private fun SubscriptionEntity.cycle() = Cycle(cycleUnit, cycleCount)

    private companion object {
        const val CHARGE_SLACK_DAYS = 3L
    }

    private fun SubscriptionEntity.toDomain(cats: Map<Long, Category>): Subscription? {
        val category = cats[categoryId] ?: return null
        return Subscription(
            id = id, name = name, amountMinor = amountMinor, currency = currency, cycle = cycle(),
            anchor = LocalDate.ofEpochDay(anchorEpochDay), nextCharge = LocalDate.ofEpochDay(nextChargeEpochDay),
            category = category, paymentMethodId = paymentMethodId, remindDaysBefore = remindDaysBefore,
            autoLog = autoLog, status = status, colorKey = colorKey, note = note,
        )
    }
}
