package com.grid.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.grid.app.core.data.db.entities.BankAccountEntity
import com.grid.app.core.data.db.entities.BankConnectionEntity
import com.grid.app.core.data.db.entities.OwnAccountRuleEntity
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.data.db.entities.TransactionEntity
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CaptureSource
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

    @Query("SELECT * FROM bank_accounts WHERE connectionId = :connectionId ORDER BY id")
    suspend fun accounts(connectionId: Long): List<BankAccountEntity>

    @Query("SELECT * FROM bank_accounts WHERE connectionId = :connectionId ORDER BY id")
    fun observeAccounts(connectionId: Long): Flow<List<BankAccountEntity>>

    /** Revolut reports the account holder's name as the account name. */
    @Query("SELECT DISTINCT name FROM bank_accounts WHERE name IS NOT NULL")
    suspend fun accountHolderNames(): List<String>

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

    @Query("DELETE FROM bank_transactions WHERE id = :id")
    suspend fun deleteStaged(id: Long)

    /** Money paid out to payees Gemini hasn't looked at yet. */
    @Query(
        """SELECT * FROM bank_transactions b WHERE b.direction = 'OUT' AND b.state = 'BOOKED' AND b.counterpartyKey IS NOT NULL
             AND b.kind IN ('CARD_SPEND', 'DIRECT_DEBIT', 'TRANSFER_OUT')
             AND NOT EXISTS (SELECT 1 FROM payee_profiles p WHERE p.`key` = b.counterpartyKey)""",
    )
    suspend fun unprofiledPayments(): List<BankTransactionEntity>

    /** Booked payments with their ledger entry: who was paid, for the entries' payee links. */
    @Query("SELECT * FROM bank_transactions WHERE state = 'BOOKED' AND transactionId IS NOT NULL AND counterpartyKey IS NOT NULL")
    suspend fun bookedWithEntry(): List<BankTransactionEntity>

    /**
     * Bank payments whose ledger entry is gone (deleted before "Recently deleted" existed, or lost): booked, unlinked,
     * and not waiting in the trash.
     */
    @Query(
        """SELECT * FROM bank_transactions b WHERE b.state = 'BOOKED' AND b.transactionId IS NULL
             AND NOT EXISTS (SELECT 1 FROM deleted_transactions d WHERE d.bankRowId = b.id)
           ORDER BY b.occurredAt DESC""",
    )
    fun observeOrphans(): Flow<List<BankTransactionEntity>>

    @Query(
        """SELECT * FROM bank_transactions b WHERE b.state = 'BOOKED' AND b.transactionId IS NULL
             AND NOT EXISTS (SELECT 1 FROM deleted_transactions d WHERE d.bankRowId = b.id)""",
    )
    suspend fun orphans(): List<BankTransactionEntity>

    /** Ledger entries no bank row points to, around a time: candidates for an orphan's lost link. */
    @Query(
        """SELECT * FROM transactions t WHERE t.type = :type AND t.amountMinor = :amountMinor AND t.currency = :currency
             AND t.occurredAt BETWEEN :fromMs AND :toMs
             AND NOT EXISTS (SELECT 1 FROM bank_transactions b WHERE b.transactionId = t.id)""",
    )
    suspend fun unlinkedEntries(type: TxType, amountMinor: Long, currency: String, fromMs: Long, toMs: Long): List<TransactionEntity>

    /** Rows stored while the bank still showed them as pending (their external id starts with [prefix]). */
    @Query("SELECT * FROM bank_transactions WHERE accountId = :accountId AND externalId LIKE :prefix || '%'")
    suspend fun stagedWithPrefix(accountId: Long, prefix: String): List<BankTransactionEntity>

    @Query("SELECT * FROM bank_transactions WHERE accountId = :accountId AND externalId = :externalId")
    suspend fun stagedByExternalId(accountId: Long, externalId: String): BankTransactionEntity?

    @Query("SELECT * FROM bank_accounts ORDER BY id")
    suspend fun allAccounts(): List<BankAccountEntity>

    @Query("SELECT * FROM bank_transactions")
    suspend fun allStaged(): List<BankTransactionEntity>

    /** Ledger entries of payments the bank still shows as pending. */
    @Query("SELECT transactionId FROM bank_transactions WHERE externalId LIKE 'p:%' AND state = 'BOOKED' AND transactionId IS NOT NULL")
    fun observePendingEntryIds(): Flow<List<Long>>

    /** Payments notified by [source] that the bank listed too (their entry is linked to a bank row). */
    @Query(
        """SELECT COUNT(*) FROM transactions t JOIN captures c ON c.id = t.captureId
           WHERE c.source = :source AND EXISTS (SELECT 1 FROM bank_transactions b WHERE b.transactionId = t.id)""",
    )
    suspend fun notifiedAndListed(source: CaptureSource): Int

    /** Every row the bank sent dated around a time, whatever Grid did with it. */
    @Query("SELECT * FROM bank_transactions WHERE occurredAt BETWEEN :fromMs AND :toMs")
    suspend fun stagedBetween(fromMs: Long, toMs: Long): List<BankTransactionEntity>

    @Query("SELECT * FROM bank_transactions WHERE state = :state ORDER BY occurredAt DESC")
    suspend fun stagedByState(state: BankTxState): List<BankTransactionEntity>

    @Query("SELECT * FROM bank_transactions WHERE state = :state ORDER BY occurredAt DESC")
    fun observeStagedByState(state: BankTxState): Flow<List<BankTransactionEntity>>

    @Query("SELECT * FROM bank_transactions WHERE transactionId = :transactionId LIMIT 1")
    suspend fun stagedLinkedTo(transactionId: Long): BankTransactionEntity?

    @Query("SELECT * FROM bank_transactions ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BankTransactionEntity>

    @Query("SELECT * FROM bank_ignore_rules WHERE counterpartyKey = :key")
    suspend fun ownAccountRule(key: String): OwnAccountRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOwnAccountRule(rule: OwnAccountRuleEntity)

    /** Ledger entries a bank transaction could be the settled version of: same direction, not yet linked to the bank. */
    @Query(
        """SELECT * FROM transactions t
           WHERE t.type = :type AND t.currency = :currency AND t.occurredAt BETWEEN :fromMs AND :toMs
             AND t.source != 'BANK'
             AND NOT EXISTS (SELECT 1 FROM bank_transactions b WHERE b.transactionId = t.id)
           ORDER BY t.occurredAt""",
    )
    suspend fun ledgerCandidates(type: TxType, currency: String, fromMs: Long, toMs: Long): List<TransactionEntity>

    @Query("UPDATE bank_transactions SET countInEpochDay = :epochDay WHERE id = :id")
    suspend fun setCountIn(id: Long, epochDay: Long?)

    @Query("SELECT * FROM transactions WHERE needsReview = 1 ORDER BY occurredAt DESC")
    fun observeNeedsReview(): Flow<List<TransactionEntity>>

    /** Bank payments still under the placeholder category, to re-check when the rules improve. */
    @Query("SELECT * FROM transactions WHERE needsReview = 1 AND source = 'BANK'")
    suspend fun bankNeedsReview(): List<TransactionEntity>

    /** Everything waiting for the user: uncategorised bank bookings plus undecided bank rows. */
    @Query("SELECT (SELECT COUNT(*) FROM transactions WHERE needsReview = 1) + (SELECT COUNT(*) FROM bank_transactions WHERE state = 'NEEDS_DECISION')")
    suspend fun countToReview(): Int
}
