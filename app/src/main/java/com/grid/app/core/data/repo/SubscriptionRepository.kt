package com.grid.app.core.data.repo

import androidx.room.withTransaction
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.MerchantRuleEntity
import com.grid.app.core.data.db.entities.SubscriptionEntity
import com.grid.app.core.data.db.entities.TransactionEntity
import com.grid.app.core.model.Category
import com.grid.app.core.model.Cycle
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.Transaction
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

    /** Earlier payments a subscription made from [payment] covers: same payee, not linked yet, about the same price. */
    suspend fun pastPaymentsLike(payment: Transaction): List<Long> {
        val key = payment.merchant?.let(MerchantKey::of) ?: return listOf(payment.id)
        return db.transactionDao().withMerchant(TxType.EXPENSE)
            .filter { it.id == payment.id || isSamePayment(it, key, payment.amountMinor) }
            .map { it.id }
    }

    /** Files [ids] under the subscription (and its category), and teaches the payee's category for later bank payments. */
    suspend fun linkPayments(subscriptionId: Long, ids: List<Long>) {
        val sub = dao.get(subscriptionId) ?: return
        val now = clock.millis()
        db.withTransaction {
            val txDao = db.transactionDao()
            ids.mapNotNull { txDao.get(it) }.forEach {
                txDao.update(it.copy(subscriptionId = sub.id, categoryId = sub.categoryId, needsReview = false, updatedAt = now))
            }
            ids.firstNotNullOfOrNull { txDao.get(it)?.merchant }?.let(MerchantKey::of)?.let { key ->
                val rules = db.merchantRuleDao()
                rules.upsert(MerchantRuleEntity(key, sub.categoryId, sub.paymentMethodId, (rules.get(key)?.hits ?: 0) + 1, now))
            }
        }
        listeners.notifyAll()
    }

    /**
     * Moves each due subscription's next charge past [today]. Subscriptions never write to the ledger (the owner's
     * rule): they plan and remind; the real payment comes from the bank and is linked to them. Entries that earlier
     * versions auto-logged are removed, unless a bank payment was merged into them.
     */
    suspend fun processDueCharges(today: LocalDate) {
        val removed = db.withTransaction {
            for (sub in dao.dueOnOrBefore(today.toEpochDay())) {
                val anchor = LocalDate.ofEpochDay(sub.anchorEpochDay)
                dao.update(sub.copy(nextChargeEpochDay = BillingSchedule.nextAfter(anchor, sub.cycle(), today).toEpochDay()))
            }
            db.transactionDao().deleteUnbackedSubscriptionCharges()
        }
        if (removed > 0) listeners.notifyAll()
    }

    private fun SubscriptionEntity.cycle() = Cycle(cycleUnit, cycleCount)

    companion object {
        /** Prices move (a plan going from €7.99 to €8.99): a quarter either way still counts as the same charge. */
        private const val PRICE_TOLERANCE = 0.25

        fun isSamePayment(row: TransactionEntity, payeeKey: String, amountMinor: Long): Boolean =
            row.subscriptionId == null && row.merchant?.let(MerchantKey::of) == payeeKey &&
                kotlin.math.abs(row.amountMinor - amountMinor) <= amountMinor * PRICE_TOLERANCE
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
