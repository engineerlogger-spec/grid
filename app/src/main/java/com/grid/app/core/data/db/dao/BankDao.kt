package com.grid.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.grid.app.core.data.db.entities.BankAccountEntity
import com.grid.app.core.data.db.entities.BankConnectionEntity
import com.grid.app.core.data.db.entities.BankIgnoreRuleEntity
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.data.db.entities.TransactionEntity
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.TxType
import kotlinx.coroutines.flow.Flow

@Dao
interface BankDao {
    /** v1 supports a single bank connection. */
    @Query("SELECT * FROM bank_connections ORDER BY id LIMIT 1")
    suspend fun connection(): BankConnectionEntity?

    @Query("SELECT * FROM bank_connections ORDER BY id LIMIT 1")
    fun observeConnection(): Flow<BankConnectionEntity?>

    @Insert
    suspend fun insertConnection(connection: BankConnectionEntity): Long

    @Update
    suspend fun updateConnection(connection: BankConnectionEntity)

    @Query("DELETE FROM bank_connections WHERE id = :id")
    suspend fun deleteConnection(id: Long)

    @Query("SELECT * FROM bank_accounts WHERE connectionId = :connectionId ORDER BY id")
    suspend fun accounts(connectionId: Long): List<BankAccountEntity>

    @Query("SELECT * FROM bank_accounts WHERE connectionId = :connectionId ORDER BY id")
    fun observeAccounts(connectionId: Long): Flow<List<BankAccountEntity>>

    @Query("SELECT * FROM bank_accounts WHERE id = :id")
    suspend fun account(id: Long): BankAccountEntity?

    @Upsert
    suspend fun upsertAccount(account: BankAccountEntity): Long

    @Update
    suspend fun updateAccount(account: BankAccountEntity)

    /** Returns -1 when this transaction (account + external id) was already fetched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStaged(row: BankTransactionEntity): Long

    @Update
    suspend fun updateStaged(row: BankTransactionEntity)

    @Query("SELECT * FROM bank_transactions WHERE id = :id")
    suspend fun staged(id: Long): BankTransactionEntity?

    @Query("SELECT * FROM bank_transactions WHERE state = :state ORDER BY occurredAt DESC")
    suspend fun stagedByState(state: BankTxState): List<BankTransactionEntity>

    @Query("SELECT * FROM bank_transactions WHERE state = :state ORDER BY occurredAt DESC")
    fun observeStagedByState(state: BankTxState): Flow<List<BankTransactionEntity>>

    @Query("SELECT * FROM bank_transactions WHERE transactionId = :transactionId LIMIT 1")
    suspend fun stagedLinkedTo(transactionId: Long): BankTransactionEntity?

    @Query("SELECT * FROM bank_transactions ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BankTransactionEntity>

    @Query("SELECT * FROM bank_ignore_rules WHERE counterpartyKey = :key")
    suspend fun ignoreRule(key: String): BankIgnoreRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIgnoreRule(rule: BankIgnoreRuleEntity)

    /** Ledger entries a bank transaction could be the settled version of: same direction, not yet linked to the bank. */
    @Query(
        """SELECT * FROM transactions t
           WHERE t.type = :type AND t.currency = :currency AND t.occurredAt BETWEEN :fromMs AND :toMs
             AND t.source != 'BANK'
             AND NOT EXISTS (SELECT 1 FROM bank_transactions b WHERE b.transactionId = t.id)
           ORDER BY t.occurredAt""",
    )
    suspend fun ledgerCandidates(type: TxType, currency: String, fromMs: Long, toMs: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE needsReview = 1 ORDER BY occurredAt DESC")
    fun observeNeedsReview(): Flow<List<TransactionEntity>>
}
