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
                payeeKey = draft.payeeKey, amountVaries = draft.amountVaries, detected = draft.detected,
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
                // The editor doesn't show the payee link: an edit keeps it.
                payeeKey = draft.payeeKey ?: existing.payeeKey, amountVaries = draft.amountVaries,
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
     * The user added a suggestion: it becomes a tracked subscription and takes its payee's past payments (about the
     * same price, or any when the amount varies).
     */
    suspend fun acceptSuggestion(id: Long) {
        val sub = dao.get(id)?.takeIf { it.status == SubscriptionStatus.SUGGESTED } ?: return
        dao.update(sub.copy(status = SubscriptionStatus.ACTIVE))
        linkPayments(id, pastPaymentsOf(sub))
    }

    /** Entries paid to a subscription's payee (by bank payee, else by name) not linked to anything yet. */
    private suspend fun pastPaymentsOf(sub: SubscriptionEntity): List<Long> {
        val nameKey = MerchantKey.of(sub.name)
        val byBank = sub.payeeKey?.let { key -> db.bankDao().bookedWithEntry().filter { it.counterpartyKey == key }.mapNotNull { it.transactionId }.toSet() }.orEmpty()
        return db.transactionDao().withMerchant(TxType.EXPENSE)
            .filter { it.subscriptionId == null && (it.id in byBank || (nameKey != null && MerchantKey.of(it.merchant!!) == nameKey)) }
            .filter { sub.amountVaries || kotlin.math.abs(it.amountMinor - sub.amountMinor) <= sub.amountMinor * PRICE_TOLERANCE }
            .map { it.id }
    }

    /** One of the user's own subscriptions is paid to this bank payee: link it, so its payments are recognised. */
    suspend fun linkPayee(id: Long, payeeKey: String) {
        val sub = dao.get(id)?.takeIf { it.payeeKey == null } ?: return
        dao.update(sub.copy(payeeKey = payeeKey))
    }

    /** Bills auto-added by 3.0–3.1 become suggestions for the user to confirm (their payment links are dropped). */
    suspend fun detectedToSuggestions() {
        db.withTransaction {
            dao.all().filter { it.detected && it.status == SubscriptionStatus.ACTIVE }.forEach {
                db.transactionDao().unlinkSubscription(it.id)
                dao.update(it.copy(status = SubscriptionStatus.SUGGESTED))
            }
        }
        listeners.notifyAll()
    }

    /** A detected bill's expected amount, kept current from its latest payments (phone, energy). */
    suspend fun refreshExpected(id: Long, amountMinor: Long, varies: Boolean) {
        val existing = dao.get(id) ?: return
        if (existing.amountMinor == amountMinor && existing.amountVaries == (existing.amountVaries || varies)) return
        dao.update(existing.copy(amountMinor = amountMinor, amountVaries = existing.amountVaries || varies))
    }

    /**
     * "Not a bill": a detected subscription is removed, its payments unlinked, and its payee is never detected again.
     */
    suspend fun dismissDetected(id: Long) {
        val existing = dao.get(id) ?: return
        db.withTransaction {
            db.transactionDao().unlinkSubscription(id)
            existing.payeeKey?.let { key ->
                val profiles = db.payeeProfileDao()
                val profile = profiles.get(key) ?: com.grid.app.core.data.db.entities.PayeeProfileEntity(key = key, name = existing.name, updatedAt = clock.millis())
                profiles.upsert(listOf(profile.copy(notABill = true, updatedAt = clock.millis())))
            }
            dao.delete(id)
        }
        listeners.notifyAll()
    }

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
                rules.upsert(MerchantRuleEntity(key, sub.categoryId, sub.paymentMethodId, (rules.get(key)?.hits ?: 0) + 1, now, userSet = !sub.detected))
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
            payeeKey = payeeKey, amountVaries = amountVaries, detected = detected,
        )
    }
}
