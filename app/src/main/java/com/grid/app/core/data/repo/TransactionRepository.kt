package com.grid.app.core.data.repo

import androidx.room.withTransaction
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.dao.CategoryUsage
import com.grid.app.core.data.db.entities.MerchantRuleEntity
import com.grid.app.core.data.db.entities.TransactionEntity
import com.grid.app.core.model.Category
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.PaymentMethod
import com.grid.app.core.model.QuickSuggestion
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TransactionDraft
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

    private val categories: Flow<Map<Long, Category>> =
        db.categoryDao().observeAll().map { list -> list.associate { it.id to it.toDomain() } }
    private val methods: Flow<Map<Long, PaymentMethod>> =
        db.paymentMethodDao().observeAll().map { list -> list.associate { it.id to it.toDomain() } }

    private fun Flow<List<TransactionEntity>>.resolved(): Flow<List<Transaction>> =
        combine(this, categories, methods) { rows, cats, ms -> rows.mapNotNull { it.toDomain(cats, ms) } }

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
            learnMerchant(draft, now)
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
            if (!categoryChanged || samePayeeToo || samePayeeIds(id, reviewed.merchant, reviewed.type).isEmpty()) learnMerchant(reviewed, now)
            if (categoryChanged && samePayeeToo) {
                samePayeeIds(id, reviewed.merchant, reviewed.type).mapNotNull { dao.get(it) }
                    .forEach { dao.update(it.copy(categoryId = reviewed.categoryId, needsReview = false, updatedAt = now)) }
            }
        }
        listeners.notifyAll()
    }

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

    /** Deletes and returns the transaction so the caller can offer Undo via [restore]. */
    suspend fun delete(id: Long): Transaction? {
        val tx = get(id) ?: return null
        dao.delete(id)
        listeners.notifyAll()
        return tx
    }

    suspend fun restore(tx: Transaction): Long {
        val id = dao.insert(tx.toDraft().toEntity(id = tx.id, createdAt = tx.createdAt, updatedAt = clock.millis()))
        listeners.notifyAll()
        return id
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

    private suspend fun learnMerchant(draft: TransactionDraft, now: Long) {
        // A placeholder category ("Other" until reviewed) must not become the merchant's rule.
        if (draft.needsReview) return
        val key = draft.merchant?.let(MerchantKey::of) ?: return
        val rules = db.merchantRuleDao()
        val hits = (rules.get(key)?.hits ?: 0) + 1
        rules.upsert(MerchantRuleEntity(key, draft.categoryId, draft.paymentMethodId, hits, now))
    }
}
