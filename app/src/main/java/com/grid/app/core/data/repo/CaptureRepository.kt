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

    /** Turns a capture into a transaction (teaching the merchant rule) and marks it added. */
    suspend fun accept(id: Long, categoryId: Long, paymentMethodId: Long? = null): Long? {
        val capture = dao.get(id) ?: return null
        if (capture.status == CaptureStatus.ADDED) return capture.transactionId
        val amount = capture.amountMinor ?: return null
        val currency = capture.currency ?: return null
        val methodId = paymentMethodId ?: methodFor(capture.source)
        val txId = transactions.add(
            TransactionDraft(
                type = if (capture.direction == CaptureDirection.IN) TxType.INCOME else TxType.EXPENSE,
                amountMinor = amount, currency = currency, categoryId = categoryId, paymentMethodId = methodId,
                merchant = capture.merchant, occurredAt = capture.postedAt, source = TxSource.CAPTURE, captureId = capture.id,
            ),
        )
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
        capture.transactionId?.let { transactions.delete(it) }
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
