package com.grid.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.grid.app.core.data.db.entities.CategoryEntity
import com.grid.app.core.data.db.entities.DeletedTransactionEntity
import com.grid.app.core.data.db.entities.MerchantRuleEntity
import com.grid.app.core.data.db.entities.PaymentMethodEntity
import com.grid.app.core.data.db.entities.RevertedPaymentEntity
import com.grid.app.core.data.db.entities.TransactionEntity
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.TxType
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY kind, position")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY kind, position")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE iconKey = :iconKey AND kind = :kind ORDER BY archived, position LIMIT 1")
    suspend fun byIconKey(iconKey: String, kind: CategoryKind): CategoryEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM categories WHERE kind = :kind")
    suspend fun maxPosition(kind: CategoryKind): Int

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Update
    suspend fun updateAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PaymentMethodDao {
    @Query("SELECT * FROM payment_methods ORDER BY position")
    fun observeAll(): Flow<List<PaymentMethodEntity>>

    @Query("SELECT * FROM payment_methods ORDER BY position")
    suspend fun all(): List<PaymentMethodEntity>

    @Query("SELECT * FROM payment_methods WHERE id = :id")
    suspend fun get(id: Long): PaymentMethodEntity?

    @Insert
    suspend fun insert(method: PaymentMethodEntity): Long

    @Update
    suspend fun update(method: PaymentMethodEntity)
}

@Dao
interface TrashDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: DeletedTransactionEntity)

    @Query("SELECT * FROM deleted_transactions WHERE id = :id")
    suspend fun get(id: Long): DeletedTransactionEntity?

    @Query("SELECT * FROM deleted_transactions ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<DeletedTransactionEntity>>

    @Query("DELETE FROM deleted_transactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM deleted_transactions WHERE deletedAt < :before")
    suspend fun deletedBefore(before: Long): List<DeletedTransactionEntity>

    @Query("DELETE FROM deleted_transactions WHERE deletedAt < :before")
    suspend fun purgeBefore(before: Long)
}

@Dao
interface RevertedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: RevertedPaymentEntity)

    @Query("SELECT * FROM reverted_payments WHERE id = :id")
    suspend fun get(id: Long): RevertedPaymentEntity?

    @Query("SELECT * FROM reverted_payments WHERE bankRowId = :bankRowId LIMIT 1")
    suspend fun byBankRow(bankRowId: Long): RevertedPaymentEntity?

    @Query("SELECT * FROM reverted_payments WHERE occurredAt >= :startMs AND occurredAt < :endMs ORDER BY occurredAt DESC")
    fun observeBetween(startMs: Long, endMs: Long): Flow<List<RevertedPaymentEntity>>

    @Query("SELECT * FROM reverted_payments WHERE occurredAt BETWEEN :fromMs AND :toMs")
    suspend fun between(fromMs: Long, toMs: Long): List<RevertedPaymentEntity>

    @Query("SELECT * FROM reverted_payments ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<RevertedPaymentEntity>>

    @Query("DELETE FROM reverted_payments WHERE id = :id")
    suspend fun delete(id: Long)
}

/** How often a category was used — drives quick-add ordering. */
data class CategoryUsage(val categoryId: Long, val uses: Int)

/** A repeated (label, amount, category) combo — drives one-tap quick-add suggestions. */
data class SuggestionRow(
    val label: String,
    val amountMinor: Long,
    val categoryId: Long,
    val paymentMethodId: Long?,
    val uses: Int,
    val lastUsed: Long,
)

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Update
    suspend fun update(tx: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE occurredAt >= :startMs AND occurredAt < :endMs ORDER BY occurredAt DESC, id DESC")
    fun observeBetween(startMs: Long, endMs: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE occurredAt >= :startMs AND occurredAt < :endMs ORDER BY occurredAt DESC, id DESC")
    suspend fun between(startMs: Long, endMs: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC, id DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query(
        """SELECT categoryId, COUNT(*) AS uses FROM transactions
           WHERE type = :type AND occurredAt >= :sinceMs
           GROUP BY categoryId ORDER BY uses DESC, MAX(occurredAt) DESC""",
    )
    suspend fun categoryUsage(type: TxType, sinceMs: Long): List<CategoryUsage>

    @Query(
        """SELECT COALESCE(merchant, note) AS label, amountMinor, categoryId, paymentMethodId,
                  COUNT(*) AS uses, MAX(occurredAt) AS lastUsed
           FROM transactions
           WHERE type = 'EXPENSE' AND occurredAt >= :sinceMs AND TRIM(COALESCE(merchant, note, '')) != ''
           GROUP BY LOWER(TRIM(COALESCE(merchant, note))), amountMinor, categoryId
           HAVING uses >= 2
           ORDER BY uses DESC, lastUsed DESC
           LIMIT :limit""",
    )
    suspend fun suggestionRows(sinceMs: Long, limit: Int): List<SuggestionRow>

    @Query("SELECT paymentMethodId FROM transactions WHERE paymentMethodId IS NOT NULL AND source = 'MANUAL' ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastManualPaymentMethodId(): Long?

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun countForCategory(categoryId: Long): Int

    @Query("SELECT * FROM transactions WHERE type = :type AND merchant IS NOT NULL")
    suspend fun withMerchant(type: TxType): List<TransactionEntity>

    @Query("UPDATE transactions SET subscriptionId = NULL WHERE subscriptionId = :subscriptionId")
    suspend fun unlinkSubscription(subscriptionId: Long)

    /** Bank-booked entries no notification has joined yet, around a time. */
    @Query(
        """SELECT * FROM transactions WHERE source = 'BANK' AND captureId IS NULL AND type = :type AND currency = :currency
             AND occurredAt BETWEEN :fromMs AND :toMs""",
    )
    suspend fun bankEntriesWithoutCapture(type: TxType, currency: String, fromMs: Long, toMs: Long): List<TransactionEntity>

    /** Bank-booked entries a notification joined. */
    @Query("SELECT * FROM transactions WHERE source = 'BANK' AND captureId IS NOT NULL")
    suspend fun bankEntriesWithCapture(): List<TransactionEntity>

    /** Entries made from a notification that no bank payment is linked to. */
    @Query(
        """SELECT * FROM transactions t WHERE t.source = 'CAPTURE' AND t.captureId IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM bank_transactions b WHERE b.transactionId = t.id)""",
    )
    suspend fun captureEntriesWithoutBank(): List<TransactionEntity>

    /** Charges older versions auto-logged for subscriptions, except those a bank payment was merged into. */
    @Query(
        """DELETE FROM transactions WHERE source = 'SUBSCRIPTION'
           AND NOT EXISTS (SELECT 1 FROM bank_transactions b WHERE b.transactionId = transactions.id)""",
    )
    suspend fun deleteUnbackedSubscriptionCharges(): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE subscriptionId = :subscriptionId AND occurredAt >= :startMs AND occurredAt < :endMs")
    suspend fun countForSubscriptionBetween(subscriptionId: Long, startMs: Long, endMs: Long): Int

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int
}

@Dao
interface MerchantRuleDao {
    @Query("SELECT * FROM merchant_rules WHERE merchantKey = :key")
    suspend fun get(key: String): MerchantRuleEntity?

    @Upsert
    suspend fun upsert(rule: MerchantRuleEntity)

    @Query("SELECT * FROM merchant_rules ORDER BY hits DESC")
    fun observeAll(): Flow<List<MerchantRuleEntity>>

    @Query("SELECT * FROM merchant_rules")
    suspend fun all(): List<MerchantRuleEntity>
}
