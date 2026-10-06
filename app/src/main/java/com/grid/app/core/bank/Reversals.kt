package com.grid.app.core.bank

import androidx.room.withTransaction
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.BankTxKind
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.CaptureSource
import com.grid.app.core.model.CaptureStatus
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Payments the bank gave back (Revolut's "Reverted": a card payment the shop released, a cancelled payment). They
 * leave the ledger and show in Activity struck through, however Grid learns it:
 * - the bank listed it as pending and dropped it without booking it ([BankSync]),
 * - the bank lists a newer card payment for the same order ([superseded]),
 * - the bank sends it again as cancelled or rejected ([cancelled]),
 * - a notification says it was reverted ([notified]),
 * - a payment notification from the bank's app that the bank never listed ([unlisted]).
 */
@Singleton
class Reversals @Inject constructor(
    private val db: GridDatabase,
    private val transactions: TransactionRepository,
    private val clock: AppClock,
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
     * Payments the bank's own app notified that the bank still doesn't list hours later, after a successful sync: they
     * were reverted before Grid saw them (the bank lists even pending card payments within minutes). Only money out,
     * in [currencies] (every account of them synced), made since the bank was connected ([since]), in the last week.
     */
    suspend fun unlisted(bankName: String, currencies: Set<String>, since: Long) {
        val source = APP_OF_BANK.entries.firstOrNull { bankName.contains(it.key, ignoreCase = true) }?.value ?: return
        val now = clock.millis()
        for (entry in db.transactionDao().captureEntriesWithoutBank()) {
            if (entry.type != TxType.EXPENSE || entry.currency !in currencies) continue
            if (entry.occurredAt < maxOf(since, now - LOOKBACK) || entry.occurredAt > now - GRACE) continue
            if (entry.captureId?.let { db.captureDao().get(it) }?.source != source) continue
            val listed = dao.stagedBetween(entry.occurredAt - LISTED_WINDOW, entry.occurredAt + LISTED_WINDOW).any {
                it.direction == CaptureDirection.OUT && it.currency == entry.currency && MatchRules.relativeDiff(it.amountMinor, entry.amountMinor) <= 0.10
            }
            if (!listed) transactions.revert(entry.id, null)
        }
    }

    /**
     * A card payment authorised again for the same order (a ride's estimate, then its final price): the bank releases
     * the first, which Revolut shows as Reverted while its feed still lists it as pending, like the new one
     * ("Paypal *bolt.eu/o/2610061" €19.70, then €23.20). Pending card payments whose descriptor carries the same order
     * number are one payment: all but the latest are reverted. One booked after all comes back ([bookedAfterAll]).
     */
    suspend fun superseded(accountId: Long) {
        dao.stagedWithPrefix(accountId, BankSync.PENDING_PREFIX)
            .filter { it.state == BankTxState.BOOKED && it.transactionId != null && it.direction == CaptureDirection.OUT && it.kind == BankTxKind.CARD_SPEND }
            .filter { it.counterparty?.let(ORDER_NUMBER::containsMatchIn) == true }
            .groupBy { it.counterparty!!.trim().lowercase() }
            .values.filter { it.size > 1 }
            .forEach { sameOrder ->
                val latest = sameOrder.maxWith(compareBy({ issuedAt(it) }, { it.id }))
                sameOrder.filter { it.id != latest.id && abs(it.bookingEpochDay - latest.bookingEpochDay) <= 1 }.forEach { revertRow(it) }
            }
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
        transactions.unrevert(id)
        bankRowId?.let { dao.staged(it) }?.takeIf { it.externalId.startsWith(BankSync.PENDING_PREFIX) }
            ?.let { dao.updateStaged(it.copy(externalId = KEPT_PREFIX + it.externalId.removePrefix(BankSync.PENDING_PREFIX))) }
    }

    /** Revolut's transaction ids start with the second they were made (hex): orders same-day payments. */
    private fun issuedAt(row: BankTransactionEntity): Long? =
        TIMED_ID.matchEntire(row.externalId.removePrefix(BankSync.PENDING_PREFIX))?.groupValues?.get(1)?.toLong(16)

    private companion object {
        /** An order number in a card descriptor ("bolt.eu/o/2610061"): a plain shop name ("Starbucks") has none. */
        val ORDER_NUMBER = Regex("""\d{5,}""")
        val TIMED_ID = Regex("""([0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
        val HOUR = TimeUnit.HOURS.toMillis(1)
        /** A reversal comes within a few days of the payment. */
        val NOTIFIED_WINDOW = TimeUnit.DAYS.toMillis(3)
        val LISTED_WINDOW = TimeUnit.DAYS.toMillis(2)
        val LOOKBACK = TimeUnit.DAYS.toMillis(7)
        /** Time for the bank to list a payment, pending or booked. */
        val GRACE = TimeUnit.HOURS.toMillis(2)
        /** Pending rows the user counted anyway: kept out of the pending check. */
        const val KEPT_PREFIX = "k:"
        /** Banks whose own app's notifications Grid reads. */
        val APP_OF_BANK = mapOf("revolut" to CaptureSource.REVOLUT)
    }
}
