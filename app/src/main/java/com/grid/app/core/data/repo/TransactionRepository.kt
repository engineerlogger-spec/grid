package com.grid.app.core.data.repo

import androidx.room.withTransaction
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.Seed
import com.grid.app.core.data.db.dao.CategoryUsage
import com.grid.app.core.data.db.entities.DeletedTransactionEntity
import com.grid.app.core.data.db.entities.MerchantRuleEntity
import com.grid.app.core.data.db.entities.RevertedPaymentEntity
import com.grid.app.core.data.db.entities.TransactionEntity
import com.grid.app.core.model.Category
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.DeletedEntry
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.PaymentMethod
import com.grid.app.core.model.QuickSuggestion
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TransactionDraft
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val db: GridDatabase,
    private val clock: AppClock,
    private val listeners: Set<@JvmSuppressWildcards LedgerListener>,
) {
    private val dao = db.transactionDao()
    private val trash = db.trashDao()
    private val reverted = db.revertedDao()

    private val categories: Flow<Map<Long, Category>> =
        db.categoryDao().observeAll().map { list -> list.associate { it.id to it.toDomain() } }
    private val methods: Flow<Map<Long, PaymentMethod>> =
        db.paymentMethodDao().observeAll().map { list -> list.associate { it.id to it.toDomain() } }

    /** Entries whose bank payment is still pending. */
    private val pendingIds: Flow<Set<Long>> = db.bankDao().observePendingEntryIds().map { it.toSet() }

    private fun Flow<List<TransactionEntity>>.resolved(): Flow<List<Transaction>> =
        combine(this, categories, methods, pendingIds) { rows, cats, ms, pending ->
            rows.mapNotNull { row -> row.toDomain(cats, ms)?.let { if (it.id in pending) it.copy(pending = true) else it } }
        }

    fun observePeriod(period: BudgetPeriod): Flow<List<Transaction>> =
        observeBetween(period.startMillis(clock.zone), period.endMillis(clock.zone))

    fun observeBetween(startMs: Long, endMs: Long): Flow<List<Transaction>> = dao.observeBetween(startMs, endMs).resolved()

    fun observeAll(): Flow<List<Transaction>> = dao.observeAll().resolved()

    fun observeRecent(limit: Int): Flow<List<Transaction>> = dao.observeRecent(limit).resolved()

    suspend fun get(id: Long): Transaction? {
        val row = dao.get(id) ?: return null
        return row.toDomain(categories.first(), methods.first())
    }

    suspend fun add(draft: TransactionDraft): Long {
        val now = clock.millis()
        val id = db.withTransaction {
            val id = dao.insert(draft.toEntity(createdAt = now, updatedAt = now))
            // Bank and subscription bookings are automatic guesses: they keep the rule warm but never count as the user's choice.
            learnMerchant(draft, now, userSet = draft.source != TxSource.BANK && draft.source != TxSource.SUBSCRIPTION)
            id
        }
        listeners.notifyAll()
        return id
    }

    /**
     * A user edit is a review: it clears [TransactionDraft.needsReview]. With [samePayeeToo], a new category also
     * goes to every other entry with the same payee and becomes the payee's rule; without it, only this entry changes.
     */
    suspend fun update(id: Long, draft: TransactionDraft, samePayeeToo: Boolean = false) {
        val existing = dao.get(id) ?: return
        val now = clock.millis()
        val reviewed = draft.copy(needsReview = false)
        val categoryChanged = existing.categoryId != reviewed.categoryId
        db.withTransaction {
            dao.update(reviewed.toEntity(id = id, createdAt = existing.createdAt, updatedAt = now))
            // A one-off exception ("this one was a gift") must not retrain the payee.
            if (!categoryChanged || samePayeeToo || samePayeeIds(id, reviewed.merchant, reviewed.type).isEmpty()) learnMerchant(reviewed, now, userSet = true)
            if (categoryChanged && samePayeeToo) {
                samePayeeIds(id, reviewed.merchant, reviewed.type).mapNotNull { dao.get(it) }
                    .forEach { dao.update(it.copy(categoryId = reviewed.categoryId, needsReview = false, updatedAt = now)) }
            }
        }
        listeners.notifyAll()
    }

    /** What Gemini found out about this entry's payee (\"Kebab shop\"), if it looked. */
    suspend fun payeeAbout(tx: Transaction): String? = tx.merchant?.let { db.payeeProfileDao().byName(it)?.about }

    /** The other entries paid to (or received from) the same payee as [tx]. */
    suspend fun samePayeeIds(tx: Transaction): List<Long> = samePayeeIds(tx.id, tx.merchant, tx.type)

    private suspend fun samePayeeIds(id: Long, merchant: String?, type: TxType): List<Long> {
        val key = merchant?.let(MerchantKey::of) ?: return emptyList()
        return dao.withMerchant(type).filter { it.id != id && MerchantKey.of(it.merchant!!) == key }.map { it.id }
    }

    /** Gives an entry filed under the placeholder its category (automatic guess: no rule is learned from it). */
    suspend fun recategorize(id: Long, categoryId: Long) {
        val existing = dao.get(id) ?: return
        dao.update(existing.copy(categoryId = categoryId, needsReview = false, updatedAt = clock.millis()))
        listeners.notifyAll()
    }

    /** Bank sync found the settled version of this entry: the bank's amount wins; [methodId] replaces the method when given. */
    suspend fun applyBank(id: Long, amountMinor: Long, methodId: Long?) {
        val existing = dao.get(id) ?: return
        dao.update(existing.copy(amountMinor = amountMinor, paymentMethodId = methodId ?: existing.paymentMethodId, updatedAt = clock.millis()))
        listeners.notifyAll()
    }

    /**
     * Deletes the transaction into "Recently deleted" (kept [TRASH_DAYS] days, restorable with its bank link) and
     * returns it so the caller can offer Undo via [restore].
     */
    suspend fun delete(id: Long): Transaction? {
        val tx = get(id) ?: return null
        val now = clock.millis()
        db.withTransaction {
            val row = dao.get(id) ?: return@withTransaction
            val bankRow = db.bankDao().stagedLinkedTo(id)
            trash.insert(
                DeletedTransactionEntity(
                    id = row.id, type = row.type, amountMinor = row.amountMinor, currency = row.currency, categoryId = row.categoryId,
                    paymentMethodId = row.paymentMethodId, merchant = row.merchant, note = row.note, occurredAt = row.occurredAt,
                    createdAt = row.createdAt, source = row.source, subscriptionId = row.subscriptionId, pendingId = row.pendingId,
                    captureId = row.captureId, needsReview = row.needsReview, bankRowId = bankRow?.id, deletedAt = now,
                ),
            )
            dao.delete(id)
            // Gone for good after the grace period: their bank rows are marked so they're never offered again.
            val expiry = now - TimeUnit.DAYS.toMillis(TRASH_DAYS)
            trash.deletedBefore(expiry).mapNotNull { it.bankRowId?.let { rowId -> db.bankDao().staged(rowId) } }
                .forEach { db.bankDao().updateStaged(it.copy(state = BankTxState.IGNORED)) }
            trash.purgeBefore(expiry)
        }
        listeners.notifyAll()
        return tx
    }

    /** Undo of [delete]. */
    suspend fun restore(tx: Transaction) {
        restoreDeleted(tx.id)
    }

    /** Puts a deleted entry back under its old id and re-links the bank payment it came from. */
    suspend fun restoreDeleted(id: Long) {
        db.withTransaction {
            val d = trash.get(id) ?: return@withTransaction
            val categoryId = existingCategory(d.categoryId, d.type)
            dao.insert(
                TransactionEntity(
                    id = d.id, type = d.type, amountMinor = d.amountMinor, currency = d.currency, categoryId = categoryId,
                    paymentMethodId = d.paymentMethodId?.takeIf { db.paymentMethodDao().get(it) != null }, merchant = d.merchant, note = d.note,
                    occurredAt = d.occurredAt, createdAt = d.createdAt, updatedAt = clock.millis(), source = d.source,
                    subscriptionId = d.subscriptionId, pendingId = d.pendingId, captureId = d.captureId, needsReview = d.needsReview,
                ),
            )
            d.bankRowId?.let { db.bankDao().staged(it) }?.takeIf { it.transactionId == null }?.let { db.bankDao().updateStaged(it.copy(transactionId = d.id)) }
            trash.delete(id)
        }
        listeners.notifyAll()
    }

    /**
     * The bank reverted this payment: it leaves the ledger (no total, budget or bill counts it) and shows in Activity
     * as "Reverted". [bankRowId]: the bank's row of it, which the caller marks REVERTED in the same transaction.
     */
    suspend fun revert(id: Long, bankRowId: Long?) {
        db.withTransaction {
            val row = dao.get(id) ?: return@withTransaction
            reverted.insert(
                RevertedPaymentEntity(
                    id = row.id, type = row.type, amountMinor = row.amountMinor, currency = row.currency, categoryId = row.categoryId,
                    paymentMethodId = row.paymentMethodId, merchant = row.merchant, note = row.note, occurredAt = row.occurredAt,
                    createdAt = row.createdAt, source = row.source, subscriptionId = row.subscriptionId, captureId = row.captureId,
                    bankRowId = bankRowId, revertedAt = clock.millis(),
                ),
            )
            dao.delete(id)
        }
        listeners.notifyAll()
    }

    /**
     * A payment Grid took for reverted goes back in the ledger, linked again to its bank row. [byUser]: "Count it
     * anyway" on a notification the bank never listed, which is then never taken for reverted again.
     */
    suspend fun unrevert(id: Long, byUser: Boolean = false) {
        db.withTransaction {
            val r = reverted.get(id) ?: return@withTransaction
            dao.insert(
                TransactionEntity(
                    id = r.id, type = r.type, amountMinor = r.amountMinor, currency = r.currency, categoryId = existingCategory(r.categoryId, r.type),
                    paymentMethodId = r.paymentMethodId?.takeIf { db.paymentMethodDao().get(it) != null }, merchant = r.merchant, note = r.note,
                    occurredAt = r.occurredAt, createdAt = r.createdAt, updatedAt = clock.millis(),
                    source = if (byUser && r.source == TxSource.CAPTURE && r.bankRowId == null) TxSource.MANUAL else r.source,
                    subscriptionId = r.subscriptionId, captureId = r.captureId,
                ),
            )
            r.bankRowId?.let { db.bankDao().staged(it) }?.let { db.bankDao().updateStaged(it.copy(state = BankTxState.BOOKED, transactionId = r.id)) }
            reverted.delete(id)
        }
        listeners.notifyAll()
    }

    /** Reverted payments dated in [period] (all of them when null), as display-only rows. */
    fun observeReverted(period: BudgetPeriod?): Flow<List<Transaction>> {
        val rows = if (period == null) reverted.observeAll() else reverted.observeBetween(period.startMillis(clock.zone), period.endMillis(clock.zone))
        return combine(rows, categories, methods) { list, cats, ms ->
            list.mapNotNull { r ->
                TransactionEntity(
                    id = r.id, type = r.type, amountMinor = r.amountMinor, currency = r.currency, categoryId = r.categoryId,
                    paymentMethodId = r.paymentMethodId, merchant = r.merchant, note = r.note, occurredAt = r.occurredAt,
                    createdAt = r.createdAt, updatedAt = r.revertedAt, source = r.source,
                ).toDomain(cats, ms)?.copy(reverted = true)
            }
        }
    }

    private suspend fun existingCategory(id: Long, type: TxType): Long = id.takeIf { db.categoryDao().get(it) != null }
        ?: db.categoryDao().byIconKey(if (type == TxType.EXPENSE) Seed.ICON_OTHER else Seed.ICON_OTHER_INCOME, if (type == TxType.EXPENSE) CategoryKind.EXPENSE else CategoryKind.INCOME)!!.id

    /** "Recently deleted", newest first. */
    fun observeDeleted(): Flow<List<DeletedEntry>> = combine(trash.observeAll(), categories, methods) { rows, cats, ms ->
        rows.mapNotNull { d ->
            TransactionEntity(
                id = d.id, type = d.type, amountMinor = d.amountMinor, currency = d.currency, categoryId = d.categoryId,
                paymentMethodId = d.paymentMethodId, merchant = d.merchant, note = d.note, occurredAt = d.occurredAt,
                createdAt = d.createdAt, updatedAt = d.deletedAt, source = d.source, needsReview = d.needsReview,
            ).toDomain(cats, ms)?.let { DeletedEntry(it, d.deletedAt) }
        }
    }

    /** Category usage over the last 90 days, most used first. */
    suspend fun categoryUsage(type: TxType): List<CategoryUsage> =
        dao.categoryUsage(type, clock.millis() - TimeUnit.DAYS.toMillis(90))

    suspend fun suggestions(limit: Int = 4): List<QuickSuggestion> {
        val cats = categories.first()
        return dao.suggestionRows(clock.millis() - TimeUnit.DAYS.toMillis(60), limit).mapNotNull { row ->
            val category = cats[row.categoryId]?.takeIf { !it.archived } ?: return@mapNotNull null
            QuickSuggestion(row.label.trim(), row.amountMinor, category, row.paymentMethodId)
        }
    }

    suspend fun lastPaymentMethodId(): Long? = dao.lastManualPaymentMethodId()

    companion object {
        /** How long deleted entries can be restored. */
        const val TRASH_DAYS = 30L
        private const val TAUGHT_GAP_MS = 2_000L
    }

    private suspend fun learnMerchant(draft: TransactionDraft, now: Long, userSet: Boolean) {
        // A placeholder category ("Other" until reviewed) must not become the merchant's rule.
        if (draft.needsReview) return
        val key = draft.merchant?.let(MerchantKey::of) ?: return
        val rules = db.merchantRuleDao()
        val existing = rules.get(key)
        val hits = (existing?.hits ?: 0) + 1
        // An automatic booking never replaces what the user chose.
        if (existing?.userSet == true && !userSet) {
            rules.upsert(existing.copy(hits = hits))
            return
        }
        rules.upsert(MerchantRuleEntity(key, draft.categoryId, draft.paymentMethodId, hits, now, userSet = userSet))
    }

    /**
     * Gemini recognised the payee of these entries: they take its real name, and its category unless the user chose
     * one for that payee (or the entry belongs to a subscription). The payee's rule follows the new name.
     */
    suspend fun applyPayeeProfile(ids: List<Long>, name: String?, categoryId: Long?) {
        if (ids.isEmpty()) return
        val now = clock.millis()
        db.withTransaction {
            val rules = db.merchantRuleDao()
            for (id in ids) {
                val tx = dao.get(id) ?: continue
                val oldKey = tx.merchant?.let(MerchantKey::of)
                val taught = oldKey?.let { rules.get(it) }?.userSet == true
                val newName = name?.trim()?.takeIf { it.isNotBlank() && it != tx.merchant }
                val newCategory = categoryId?.takeIf { !taught && tx.subscriptionId == null && it != tx.categoryId }
                if (newName == null && newCategory == null) continue
                dao.update(
                    tx.copy(
                        merchant = newName ?: tx.merchant, categoryId = newCategory ?: tx.categoryId,
                        needsReview = if (newCategory != null) false else tx.needsReview, updatedAt = now,
                    ),
                )
                val newKey = newName?.let(MerchantKey::of)
                if (oldKey != null && newKey != null && newKey != oldKey && rules.get(newKey) == null) {
                    rules.get(oldKey)?.let { rules.upsert(it.copy(merchantKey = newKey)) }
                }
            }
        }
        listeners.notifyAll()
    }

    /**
     * Rules from before Grid recorded who chose a category: one changed after the payee's last booking was the user's
     * edit, so it is marked as theirs.
     */
    suspend fun markTaughtRules() {
        val rules = db.merchantRuleDao()
        val latest = (dao.withMerchant(TxType.EXPENSE) + dao.withMerchant(TxType.INCOME))
            .groupBy { MerchantKey.of(it.merchant!!) }
            .mapValues { (_, txs) -> txs.maxOf { it.createdAt } }
        rules.all().filter { !it.userSet }.forEach { rule ->
            val lastBooked = latest[rule.merchantKey] ?: return@forEach
            if (rule.updatedAt > lastBooked + TAUGHT_GAP_MS) rules.upsert(rule.copy(userSet = true))
        }
    }
}
