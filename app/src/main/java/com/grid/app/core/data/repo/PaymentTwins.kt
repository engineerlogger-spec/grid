package com.grid.app.core.data.repo

import androidx.room.withTransaction
import com.grid.app.core.bank.MatchRules
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.TransactionEntity
import com.grid.app.core.model.PaymentKind
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * One payment, two sources: the bank's entry and a payment notification. Whichever arrives second joins the first,
 * so a payment is never counted twice. (Bank-after-notification is handled by the bank reconciler; this covers a
 * notification arriving after the bank already booked the payment, and cleans up pairs made before.)
 */
@Singleton
class PaymentTwins @Inject constructor(
    private val db: GridDatabase,
    private val clock: AppClock,
    private val listeners: Set<@JvmSuppressWildcards LedgerListener>,
) {
    private val txDao = db.transactionDao()

    /** The bank's entry for a payment a notification just reported, if the bank already booked it. */
    suspend fun bankEntryFor(type: TxType, amountMinor: Long, currency: String, at: Long, merchant: String?): TransactionEntity? {
        val near = txDao.bankEntriesWithoutCapture(type, currency, at - WINDOW, at + WINDOW)
        // A similar name and about the same amount (card currency conversion can move it a little)…
        near.filter { MatchRules.similar(it.merchant, merchant) && MatchRules.relativeDiff(it.amountMinor, amountMinor) <= 0.10 }
            .minByOrNull { abs(it.occurredAt - at) }?.let { return it }
        // …or, when the bank names the shop differently, the one entry of exactly that amount around that time.
        return near.filter { it.amountMinor == amountMinor && abs(it.occurredAt - at) <= EXACT_WINDOW }.singleOrNull()
    }

    /**
     * The notification joins the bank's entry: it brings the real time of day and the wallet it was paid with
     * (Google Wallet, PayPal); the bank's amount, name and category stay.
     */
    suspend fun join(bankEntry: TransactionEntity, captureId: Long, at: Long, methodId: Long?) {
        val methods = db.paymentMethodDao().all()
        val current = bankEntry.paymentMethodId?.let { id -> methods.firstOrNull { it.id == id }?.kind }
        val betterMethod = methodId?.takeIf { current == null || current == PaymentKind.REVOLUT || current == PaymentKind.CARD }
        txDao.update(
            bankEntry.copy(captureId = captureId, occurredAt = at, paymentMethodId = betterMethod ?: bankEntry.paymentMethodId, updatedAt = clock.millis()),
        )
    }

    /** Pairs left by earlier versions: a notification's entry with the bank's own entry of the same payment. */
    suspend fun mergeExisting(): Int {
        var merged = 0
        db.withTransaction {
            for (captured in txDao.captureEntriesWithoutBank()) {
                val bankEntry = bankEntryFor(captured.type, captured.amountMinor, captured.currency, captured.occurredAt, captured.merchant) ?: continue
                join(bankEntry, captured.captureId ?: continue, captured.occurredAt, captured.paymentMethodId)
                // A category the user picked on the notification wins over an automatic one.
                if (bankEntry.needsReview) txDao.update(txDao.get(bankEntry.id)!!.copy(categoryId = captured.categoryId, needsReview = false))
                db.captureDao().get(captured.captureId)?.let { db.captureDao().update(it.copy(transactionId = bankEntry.id)) }
                txDao.delete(captured.id)
                merged++
            }
        }
        if (merged > 0) listeners.notifyAll()
        return merged
    }

    private companion object {
        val WINDOW = TimeUnit.DAYS.toMillis(2)
        /** The bank books on a date (shown at noon); a notification has the real time: within a day and a half. */
        val EXACT_WINDOW = TimeUnit.HOURS.toMillis(36)
    }
}
