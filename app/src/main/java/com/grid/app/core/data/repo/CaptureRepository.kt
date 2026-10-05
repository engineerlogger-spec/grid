package com.grid.app.core.data.repo

import com.grid.app.core.capture.CaptureParse
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.CaptureEntity
import com.grid.app.core.data.db.entities.MerchantRuleEntity
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.CaptureSource
import com.grid.app.core.model.CaptureStatus
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.PaymentKind
import com.grid.app.core.model.TransactionDraft
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A payment detected from a notification, as shown in the Detected inbox. */
data class CaptureItem(
    val id: Long,
    val source: CaptureSource,
    val postedAt: Long,
    val title: String,
    val text: String,
    val amountMinor: Long?,
    val currency: String?,
    val merchant: String?,
    val direction: CaptureDirection,
    val status: CaptureStatus,
    val transactionId: Long?,
)

@Singleton
class CaptureRepository @Inject constructor(
    private val db: GridDatabase,
    private val transactions: TransactionRepository,
    private val clock: AppClock,
    private val twins: PaymentTwins,
) {
    private val dao = db.captureDao()

    fun observeInbox(): Flow<List<CaptureItem>> = dao.observeInbox().map { list -> list.map { it.toItem() } }
    fun observeUnparsed(): Flow<List<CaptureItem>> = dao.observeUnparsed().map { list -> list.map { it.toItem() } }
    fun observeRecentlyAdded(): Flow<List<CaptureItem>> =
        dao.observeAddedSince(clock.millis() - TimeUnit.DAYS.toMillis(7)).map { list -> list.map { it.toItem() } }

    suspend fun get(id: Long): CaptureItem? = dao.get(id)?.toItem()

    /**
     * Stores a parsed notification. Returns null when it duplicates one seen in the last 10 minutes
     * (apps often re-post or update the same notification).
     */
    suspend fun recordParsed(source: CaptureSource, title: String, text: String, postedAt: Long, parsed: CaptureParse.Parsed): CaptureItem? {
        val window = TimeUnit.MINUTES.toMillis(10)
        if (dao.findSimilar(source, parsed.amount.minor, parsed.amount.currency, postedAt - window, postedAt + window) != null) return null
        val entity = CaptureEntity(
            source = source, postedAt = postedAt, title = title, text = text,
            amountMinor = parsed.amount.minor, currency = parsed.amount.currency, merchant = parsed.merchant,
            direction = parsed.direction, status = CaptureStatus.NEW,
            dedupeKey = "${source.name}|${parsed.amount.minor}|${parsed.amount.currency}|${parsed.merchant?.let(MerchantKey::of)}|${postedAt / window}",
        )
        val id = dao.insert(entity)
        return if (id < 0) null else entity.copy(id = id).toItem()
    }

    /** Diagnostics: keeps the raw text of a payment-like notification that couldn't be parsed. */
    suspend fun recordUnparsed(source: CaptureSource, title: String, text: String, postedAt: Long) {
        dao.insert(
            CaptureEntity(
                source = source, postedAt = postedAt, title = title, text = text, status = CaptureStatus.UNPARSED,
                dedupeKey = "u|${source.name}|${(title + text).hashCode()}|${postedAt / TimeUnit.MINUTES.toMillis(10)}",
            ),
        )
    }

    /** The learned rule for this merchant, if the user categorised it before. */
    suspend fun ruleFor(merchant: String?): MerchantRuleEntity? = merchant?.let(MerchantKey::of)?.let { db.merchantRuleDao().get(it) }

    /**
     * When this payment is already recorded (the bank booked it, or another app's notification did), the
     * notification joins that entry instead of adding a second one. Returns that entry's id, or null when it's new.
     */
    suspend fun joinTwin(id: Long): Long? {
        val capture = dao.get(id) ?: return null
        if (capture.status == CaptureStatus.ADDED) return capture.transactionId
        val amount = capture.amountMinor ?: return null
        val currency = capture.currency ?: return null
        val type = if (capture.direction == CaptureDirection.IN) TxType.INCOME else TxType.EXPENSE
        val twin = twins.twinOf(type, amount, currency, capture.postedAt, capture.merchant, EntryOrigin.NOTIFICATION, capture.id) ?: return null
        twins.join(twin, capture.id, capture.postedAt, methodFor(capture.source))
        dao.update(capture.copy(status = CaptureStatus.ADDED, transactionId = twin.id))
        return twin.id
    }

    /**
     * Turns a capture into an entry (teaching the merchant rule) and marks it added — through [PaymentTwins], so a
     * payment already recorded is joined, not added twice. The category the user picked applies either way.
     */
    suspend fun accept(id: Long, categoryId: Long, paymentMethodId: Long? = null): Long? {
        val capture = dao.get(id) ?: return null
        if (capture.status == CaptureStatus.ADDED) return capture.transactionId
        val amount = capture.amountMinor ?: return null
        val currency = capture.currency ?: return null
        val draft = TransactionDraft(
            type = if (capture.direction == CaptureDirection.IN) TxType.INCOME else TxType.EXPENSE,
            amountMinor = amount, currency = currency, categoryId = categoryId, paymentMethodId = paymentMethodId ?: methodFor(capture.source),
            merchant = capture.merchant, occurredAt = capture.postedAt, source = TxSource.CAPTURE, captureId = capture.id,
        )
        val txId = when (val result = twins.record(draft, EntryOrigin.NOTIFICATION)) {
            is RecordResult.Added -> result.id
            is RecordResult.Joined -> result.id.also { joined ->
                // The user just said what this payment is: the entry already there takes that category.
                transactions.get(joined)?.takeIf { it.category.id != categoryId }?.let { transactions.update(it.id, it.toDraft().copy(categoryId = categoryId)) }
            }
            is RecordResult.PossibleDuplicate -> result.existing.id
        }
        dao.update(capture.copy(status = CaptureStatus.ADDED, transactionId = txId))
        return txId
    }

    suspend fun dismiss(id: Long) {
        val capture = dao.get(id) ?: return
        dao.update(capture.copy(status = CaptureStatus.DISMISSED))
    }

    /** Undo an (auto-)add: removes the transaction and puts the capture back in the inbox. */
    suspend fun undo(id: Long) {
        val capture = dao.get(id) ?: return
        val entry = capture.transactionId?.let { db.transactionDao().get(it) }
        if (entry != null && entry.source == TxSource.BANK) {
            // It joined the bank's own entry: only the notification is undone, the bank's payment stays.
            db.transactionDao().update(entry.copy(captureId = null, updatedAt = clock.millis()))
            dao.update(capture.copy(status = CaptureStatus.DISMISSED, transactionId = null))
            return
        }
        entry?.let { transactions.delete(it.id) }
        dao.update(capture.copy(status = CaptureStatus.NEW, transactionId = null))
    }

    suspend fun pruneDiagnostics() = dao.pruneUnparsed(clock.millis() - TimeUnit.DAYS.toMillis(30))
    suspend fun clearDiagnostics() = dao.clearUnparsed()

    suspend fun methodFor(source: CaptureSource): Long? {
        val kind = when (source) {
            CaptureSource.GOOGLE_WALLET -> PaymentKind.GOOGLE_WALLET
            CaptureSource.PAYPAL -> PaymentKind.PAYPAL
            CaptureSource.REVOLUT -> PaymentKind.REVOLUT
        }
        return db.paymentMethodDao().all().firstOrNull { it.kind == kind && !it.archived }?.id
    }

    private fun CaptureEntity.toItem() = CaptureItem(id, source, postedAt, title, text, amountMinor, currency, merchant, direction, status, transactionId)
}
