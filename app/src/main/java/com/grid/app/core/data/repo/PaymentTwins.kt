package com.grid.app.core.data.repo

import androidx.room.withTransaction
import com.grid.app.core.bank.MatchRules
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.TransactionEntity
import com.grid.app.core.model.CaptureStatus
import com.grid.app.core.model.PaymentKind
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TransactionDraft
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Where a new entry comes from: decides what counts as "the same payment" and what happens then. */
enum class EntryOrigin {
    /** A payment notification (Revolut, Google Wallet, PayPal): joins an entry already there. */
    NOTIFICATION,
    /** Typed in by the user or asked to the assistant: a likely duplicate is reported, not added. */
    MANUAL,
}

sealed interface RecordResult {
    data class Added(val id: Long) : RecordResult
    /** The payment was already recorded: this joined that entry. */
    data class Joined(val id: Long) : RecordResult
    /** Looks already recorded: nothing added; the caller asks the user (and may record with force). */
    data class PossibleDuplicate(val existing: Transaction) : RecordResult
}

/**
 * The one way new entries enter the ledger, so a payment is never counted twice whichever way it arrives:
 * the bank (the reconciler joins notification/manual entries; [settleNotifications] then clears matching
 * notifications still waiting in Detected), notifications (they join the bank's entry or another app's
 * notification of the same payment), and entries typed by the user or the assistant (a likely duplicate is
 * reported first). [mergeExisting] cleans up pairs made before this existed.
 */
@Singleton
class PaymentTwins @Inject constructor(
    private val db: GridDatabase,
    private val transactions: TransactionRepository,
    private val clock: AppClock,
    private val listeners: Set<@JvmSuppressWildcards LedgerListener>,
) {
    private val txDao = db.transactionDao()

    /** Records [draft] unless the same payment is already in the ledger (see [EntryOrigin]). */
    suspend fun record(draft: TransactionDraft, origin: EntryOrigin, force: Boolean = false): RecordResult {
        val twin = twinOf(draft.type, draft.amountMinor, draft.currency, draft.occurredAt, draft.merchant ?: draft.note, origin, draft.captureId)
        if (twin != null) when (origin) {
            EntryOrigin.NOTIFICATION -> {
                join(twin, draft.captureId, draft.occurredAt, draft.paymentMethodId)
                return RecordResult.Joined(twin.id)
            }
            EntryOrigin.MANUAL -> if (!force) return RecordResult.PossibleDuplicate(transactions.get(twin.id)!!)
        }
        return RecordResult.Added(transactions.add(draft))
    }

    /** The entry already recording this payment, if any. */
    suspend fun twinOf(type: TxType, amountMinor: Long, currency: String, at: Long, name: String?, origin: EntryOrigin, captureId: Long? = null): TransactionEntity? {
        val near = txDao.between(at - WINDOW, at + WINDOW).filter { it.type == type && it.currency == currency && it.source != TxSource.CHECKIN }
        return when (origin) {
            EntryOrigin.NOTIFICATION -> {
                // The bank's entry of this payment (no notification joined yet), when both name the same payee…
                val bank = near.filter { it.source == TxSource.BANK && it.captureId == null }
                bank.filter { sameNamedPayee(it.merchant, name) && MatchRules.relativeDiff(it.amountMinor, amountMinor) <= 0.10 }
                    .minByOrNull { abs(it.occurredAt - at) }
                    // …or, when the bank names the shop differently, the one entry of exactly that amount around then;
                    ?: bank.filter { it.amountMinor == amountMinor && abs(it.occurredAt - at) <= EXACT_WINDOW }.singleOrNull()
                    // …or another app's notification of the same payment (Google Wallet and Revolut both notify a card payment).
                    ?: near.filter {
                        it.source == TxSource.CAPTURE && it.captureId != captureId && it.amountMinor == amountMinor &&
                            abs(it.occurredAt - at) <= SAME_MOMENT && MatchRules.similar(it.merchant, name)
                    }.minByOrNull { abs(it.occurredAt - at) }
            }
            // Typed in: the same amount within a day. Names aren't compared ("coffee" vs the bank's "Starbucks"):
            // it's only a question to the user, who can add it anyway.
            EntryOrigin.MANUAL -> near.filter { it.amountMinor == amountMinor && abs(it.occurredAt - at) <= TimeUnit.DAYS.toMillis(1) }
                .minByOrNull { abs(it.occurredAt - at) }
        }
    }

    /**
     * A notification joins the entry already recording its payment: it brings the real time of day (bank entries
     * only have a date) and the wallet used (Google Wallet, PayPal); amount, name and category stay.
     */
    suspend fun join(entry: TransactionEntity, captureId: Long?, at: Long, methodId: Long?) {
        if (entry.source != TxSource.BANK) return // another notification's entry: nothing to improve
        val methods = db.paymentMethodDao().all()
        val current = entry.paymentMethodId?.let { id -> methods.firstOrNull { it.id == id }?.kind }
        val betterMethod = methodId?.takeIf { current == null || current == PaymentKind.REVOLUT || current == PaymentKind.CARD }
        txDao.update(entry.copy(captureId = captureId ?: entry.captureId, occurredAt = at, paymentMethodId = betterMethod ?: entry.paymentMethodId, updatedAt = clock.millis()))
        listeners.notifyAll()
    }

    /**
     * The bank just booked [entryId]: a notification of the same payment still waiting in Detected is settled by it
     * (joined, gone from the inbox), so it can't be accepted into a second entry.
     */
    suspend fun settleNotifications(entryId: Long) {
        val entry = txDao.get(entryId)?.takeIf { it.source == TxSource.BANK && it.captureId == null } ?: return
        val captures = db.captureDao()
        val waiting = captures.inboxBetween(entry.occurredAt - WINDOW, entry.occurredAt + WINDOW)
            .filter { it.amountMinor != null && it.currency == entry.currency && (it.direction.name == "OUT") == (entry.type == TxType.EXPENSE) }
        val match = waiting.filter { sameNamedPayee(entry.merchant, it.merchant) && MatchRules.relativeDiff(entry.amountMinor, it.amountMinor!!) <= 0.10 }
            .minByOrNull { abs(it.postedAt - entry.occurredAt) }
            ?: waiting.filter { it.amountMinor == entry.amountMinor && abs(it.postedAt - entry.occurredAt) <= EXACT_WINDOW }.singleOrNull()
            ?: return
        join(entry, match.id, match.postedAt, null)
        captures.update(match.copy(status = CaptureStatus.ADDED, transactionId = entry.id))
    }

    /**
     * A close amount is the same payment only when both name the same payee: a notification without a name (or a bank
     * entry without one) joins only on the exact amount. Earlier versions let it join anything within 10% (a €12.60
     * Bolt notification moved the €11.78 Allianz payment to that day).
     */
    private fun sameNamedPayee(a: String?, b: String?) = !a.isNullOrBlank() && !b.isNullOrBlank() && MatchRules.similar(a, b)

    /**
     * Undoes those joins: the bank's entry gets its own date and method back, and the notification, which named no one
     * and matches nothing for sure, is set aside (the bank lists every payment anyway).
     */
    private suspend fun undoNamelessJoins(): Int {
        var undone = 0
        val methods = db.paymentMethodDao().all().filter { !it.archived }
        for (entry in txDao.bankEntriesWithCapture()) {
            val capture = entry.captureId?.let { db.captureDao().get(it) } ?: continue
            if (!capture.merchant.isNullOrBlank() || capture.amountMinor == entry.amountMinor) continue
            val row = db.bankDao().stagedLinkedTo(entry.id) ?: continue
            val method = row.via?.let { via -> methods.firstOrNull { it.kind == via }?.id } ?: methods.firstOrNull { it.kind == PaymentKind.REVOLUT }?.id
            txDao.update(entry.copy(captureId = null, occurredAt = row.occurredAt, paymentMethodId = method ?: entry.paymentMethodId, updatedAt = clock.millis()))
            db.captureDao().update(capture.copy(status = CaptureStatus.DISMISSED, transactionId = null))
            undone++
        }
        return undone
    }

    /** Pairs left by earlier versions: notification entries recording a payment the bank (or another notification) already has. */
    suspend fun mergeExisting(): Int {
        var merged = 0
        db.withTransaction {
            merged += undoNamelessJoins()
            for (captured in txDao.captureEntriesWithoutBank()) {
                if (txDao.get(captured.id) == null) continue // merged away earlier in this pass
                val twin = twinOf(captured.type, captured.amountMinor, captured.currency, captured.occurredAt, captured.merchant, EntryOrigin.NOTIFICATION, captured.captureId)
                    ?.takeIf { it.id != captured.id } ?: continue
                // Keep the bank's entry, or the earlier of two notification entries.
                val keep = if (twin.source == TxSource.BANK || twin.occurredAt <= captured.occurredAt) twin else captured
                val drop = if (keep.id == twin.id) captured else twin
                join(keep, drop.captureId, drop.occurredAt, drop.paymentMethodId)
                // A category the user picked on the notification wins over an automatic "Other".
                txDao.get(keep.id)?.takeIf { it.needsReview }?.let { txDao.update(it.copy(categoryId = drop.categoryId, needsReview = false)) }
                drop.captureId?.let { db.captureDao().get(it) }?.let { db.captureDao().update(it.copy(transactionId = keep.id)) }
                txDao.delete(drop.id)
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
        /** Two apps notifying the same card payment do it within minutes. */
        val SAME_MOMENT = TimeUnit.MINUTES.toMillis(30)
    }
}
