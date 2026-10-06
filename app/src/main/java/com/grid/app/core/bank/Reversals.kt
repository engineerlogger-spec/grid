package com.grid.app.core.bank

import androidx.room.withTransaction
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.CaptureSource
import com.grid.app.core.model.CaptureStatus
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Payments the bank gave back (Revolut's "Reverted": a ride's first price released when the final one is charged, a
 * card payment the shop cancelled). Revolut drops them from its feed without a word, so Grid learns it when:
 * - the bank listed it as pending and dropped it without booking it ([BankSync]),
 * - the bank sends it again as cancelled or rejected ([cancelled]),
 * - a notification says it was reverted ([notified]),
 * - a payment notification (Revolut, or PayPal and Google Wallet when they pay through the bank) that the bank still
 *   doesn't list after a sync ([unlisted]): reverted before Grid synced.
 * They leave the ledger and show in Activity struck through. When the bank lists or books one after all, it comes
 * back ([stillListed], [listedAfterAll], [bookedAfterAll]).
 */
@Singleton
class Reversals @Inject constructor(
    private val db: GridDatabase,
    private val transactions: TransactionRepository,
) {
    private val dao = db.bankDao()

    /** [row]'s ledger entry becomes a reverted payment; the row stays, marked, so no later sync books it again. */
    suspend fun revertRow(row: BankTransactionEntity) = db.withTransaction {
        dao.updateStaged(row.copy(state = BankTxState.REVERTED, transactionId = null))
        row.transactionId?.let { transactions.revert(it, row.id) }
    }

    /** The bank sent [externalId] with a cancelled or rejected status: the payment Grid has under that id is reverted. */
    suspend fun cancelled(accountId: Long, externalId: String) {
        val row = dao.stagedByExternalId(accountId, externalId)
            ?: dao.stagedByExternalId(accountId, BankSync.PENDING_PREFIX + externalId)
            ?: return
        if (row.state != BankTxState.REVERTED && row.transactionId != null) revertRow(row)
    }

    /**
     * A notification said a payment of [amountMinor] (to [merchant]) was reverted: its entry, or the notification of it
     * still waiting in Detected. False when nothing matched.
     */
    suspend fun notified(amountMinor: Long, currency: String, merchant: String?, at: Long): Boolean {
        val entries = db.transactionDao().between(at - NOTIFIED_WINDOW, at + HOUR).filter {
            it.type == TxType.EXPENSE && it.currency == currency && it.amountMinor == amountMinor &&
                it.source != TxSource.CHECKIN && it.source != TxSource.SUBSCRIPTION
        }
        val entry = entries.filter { MatchRules.similar(it.merchant, merchant) }.maxByOrNull { it.occurredAt } ?: entries.singleOrNull()
        if (entry != null) {
            val row = dao.stagedLinkedTo(entry.id)
            if (row != null) revertRow(row) else transactions.revert(entry.id, null)
            return true
        }
        val waiting = db.captureDao().inboxBetween(at - NOTIFIED_WINDOW, at + HOUR)
            .filter { it.amountMinor == amountMinor && it.currency == currency && it.direction == CaptureDirection.OUT }
        val capture = waiting.filter { MatchRules.similar(it.merchant, merchant) }.maxByOrNull { it.postedAt } ?: waiting.singleOrNull() ?: return false
        db.captureDao().update(capture.copy(status = CaptureStatus.DISMISSED))
        return true
    }

    /**
     * Payments notified by an app that pays through the bank, that the bank still doesn't list (no row linked, none of
     * that exact amount around then) half an hour or more before its last successful fetch at [syncedAt]: reverted
     * before Grid saw them pending (the bank lists card payments within minutes). Only money out, in [currencies] (every
     * account of them synced), since the bank was connected ([since]), over the last week.
     */
    suspend fun unlisted(bankName: String, currencies: Set<String>, since: Long, syncedAt: Long) {
        val covered = CaptureSource.entries.filter { source ->
            // The bank's own app; PayPal and Google Wallet once their payments have shown up at the bank.
            bankName.contains(source.name, ignoreCase = true) || dao.notifiedAndListed(source) >= LEARNED_AFTER
        }.toSet()
        if (covered.isEmpty()) return
        for (entry in db.transactionDao().captureEntriesWithoutBank()) {
            if (entry.type != TxType.EXPENSE || entry.currency !in currencies) continue
            if (entry.occurredAt < maxOf(since, syncedAt - LOOKBACK) || entry.occurredAt > syncedAt - GRACE) continue
            if (entry.captureId?.let { db.captureDao().get(it) }?.source !in covered) continue
            if (listedNear(entry.amountMinor, entry.currency, entry.occurredAt).isEmpty()) transactions.revert(entry.id, null)
        }
    }

    /** Bank rows of exactly this payment out around [at] that the bank still stands by. */
    private suspend fun listedNear(amountMinor: Long, currency: String, at: Long) =
        dao.stagedBetween(at - LISTED_WINDOW, at + LISTED_WINDOW).filter {
            it.direction == CaptureDirection.OUT && it.currency == currency && it.amountMinor == amountMinor &&
                it.state != BankTxState.REVERTED && it.state != BankTxState.IGNORED
        }

    /**
     * The bank now lists [row], a payment Grid had taken for reverted because the bank didn't list it (notified only):
     * the entry comes back as this row's. False when it wasn't one of those.
     */
    suspend fun listedAfterAll(row: BankTransactionEntity): Boolean = db.withTransaction {
        if (row.direction != CaptureDirection.OUT || row.amountMinor == 0L) return@withTransaction false
        val gone = db.revertedDao().between(row.occurredAt - LISTED_WINDOW, row.occurredAt + LISTED_WINDOW)
            .filter { it.bankRowId == null && it.type == TxType.EXPENSE && it.currency == row.currency && it.amountMinor == row.amountMinor }
            .minByOrNull { abs(it.occurredAt - row.occurredAt) } ?: return@withTransaction false
        transactions.unrevert(gone.id)
        dao.updateStaged(row.copy(state = BankTxState.BOOKED, transactionId = gone.id))
        true
    }

    /** Pending payments Grid took for reverted that the bank still lists as pending ([pendingIds]): they come back. */
    suspend fun stillListed(accountId: Long, pendingIds: Set<String>) {
        for (row in dao.stagedWithPrefix(accountId, BankSync.PENDING_PREFIX)) {
            if (row.state == BankTxState.REVERTED && row.externalId in pendingIds) restoreRow(row)
        }
    }

    /**
     * Once: 3.4.1 took the first of two pending card payments with the same descriptor for replaced by the second
     * ("Paypal *bolt.eu/o/2610061" names a day of Bolt rides, not one ride). Those still listed as pending come back.
     */
    suspend fun undoSameDescriptorGuess() {
        for (account in db.bankDao().allAccounts()) {
            val pending = dao.stagedWithPrefix(account.id, BankSync.PENDING_PREFIX)
            pending.filter { it.state == BankTxState.REVERTED }
                .filter { gone -> pending.any { it.state == BankTxState.BOOKED && it.counterparty.equals(gone.counterparty, ignoreCase = true) } }
                .forEach { restoreRow(it) }
        }
    }

    private suspend fun restoreRow(row: BankTransactionEntity) {
        val entry = db.revertedDao().byBankRow(row.id)
        if (entry != null) transactions.unrevert(entry.id) else dao.updateStaged(row.copy(state = BankTxState.NEW))
    }

    /**
     * The bank booked [booked], whose pending version Grid had taken for reverted: the entry comes back, now the
     * booked payment's (no second entry). False when it wasn't one of those.
     */
    suspend fun bookedAfterAll(booked: BankTransactionEntity): Boolean = db.withTransaction {
        val pending = dao.stagedByExternalId(booked.accountId, BankSync.PENDING_PREFIX + booked.externalId)
            ?.takeIf { it.state == BankTxState.REVERTED } ?: return@withTransaction false
        val entry = db.revertedDao().byBankRow(pending.id)
        dao.deleteStaged(pending.id)
        if (entry == null) return@withTransaction false
        transactions.unrevert(entry.id)
        dao.updateStaged(booked.copy(state = BankTxState.BOOKED, transactionId = entry.id))
        if (booked.amountMinor != entry.amountMinor) transactions.applyBank(entry.id, booked.amountMinor, null)
        true
    }

    /** "Count it anyway": the payment goes back in the ledger and its bank row is no longer watched as pending. */
    suspend fun countAnyway(id: Long) = db.withTransaction {
        val bankRowId = db.revertedDao().get(id)?.bankRowId
        transactions.unrevert(id, byUser = true)
        bankRowId?.let { dao.staged(it) }?.takeIf { it.externalId.startsWith(BankSync.PENDING_PREFIX) }
            ?.let { dao.updateStaged(it.copy(externalId = KEPT_PREFIX + it.externalId.removePrefix(BankSync.PENDING_PREFIX))) }
    }

    private companion object {
        val HOUR = TimeUnit.HOURS.toMillis(1)
        /** A reversal comes within a few days of the payment. */
        val NOTIFIED_WINDOW = TimeUnit.DAYS.toMillis(3)
        val LISTED_WINDOW = TimeUnit.DAYS.toMillis(2)
        val LOOKBACK = TimeUnit.DAYS.toMillis(7)
        /** Time for the bank to list a payment it made (it shows card payments as pending within minutes). */
        val GRACE = TimeUnit.MINUTES.toMillis(30)
        /** Notifications of an app matched to bank payments before Grid trusts that it pays through the bank. */
        const val LEARNED_AFTER = 2
        /** Pending rows the user counted anyway: kept out of the pending check. */
        const val KEPT_PREFIX = "k:"
    }
}
