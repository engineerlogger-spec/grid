package com.grid.app.core.bills

import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A past payment, as recurring detection sees it. */
data class PastPayment(val merchant: String, val amountMinor: Long, val date: LocalDate, val iconKey: String?, val colorKey: String)

/** A payment that comes back every month (rent, phone, insurance, streaming…), with its expected next date. */
data class RecurringPayment(
    val merchant: String,
    val amountMinor: Long,
    val nextDate: LocalDate,
    val lastDate: LocalDate,
    val occurrences: Int,
    val iconKey: String?,
    val colorKey: String,
)

/**
 * Finds monthly payments in the history, to forecast them: Revolut doesn't share scheduled payments over Open
 * Banking, but rent, telecom, energy, insurance and subscriptions show a clear rhythm — about a month apart,
 * about the same amount.
 */
object RecurringDetector {
    private const val MIN_OCCURRENCES = 3
    private const val AMOUNT_TOLERANCE = 0.25
    /** Older than this since the last charge: probably cancelled. */
    private const val STALE_DAYS = 45L
    /** Up to this many days late (bank booking delays, weekends), a charge is still expected: shown as due today. */
    private const val GRACE_DAYS = 7L

    /** Habits, not bills: a monthly pizza night isn't a payment to forecast. */
    private val notBills = setOf("restaurant", "groceries", "shopping", "clothing", "cash", "personal_care", "entertainment")

    /** Monthly bills found in the ledger's last 400 days of spending (bank, captured and typed-in payments). */
    fun fromLedger(txs: List<Transaction>, today: LocalDate, zone: ZoneId): List<RecurringPayment> {
        val since = today.minusDays(400)
        return detect(
            txs.mapNotNull { tx ->
                val date = Instant.ofEpochMilli(tx.occurredAt).atZone(zone).toLocalDate()
                if (tx.type != TxType.EXPENSE || tx.ownTransfer || date.isBefore(since)) return@mapNotNull null
                if (tx.source != TxSource.BANK && tx.source != TxSource.CAPTURE && tx.source != TxSource.MANUAL) return@mapNotNull null
                PastPayment(tx.merchant ?: return@mapNotNull null, tx.amountMinor, date, tx.category.iconKey, tx.category.colorKey)
            },
            today,
        )
    }

    fun detect(payments: List<PastPayment>, today: LocalDate): List<RecurringPayment> =
        payments.filter { it.iconKey !in notBills }
            .groupBy { MerchantKey.of(it.merchant) ?: it.merchant }
            .values
            .mapNotNull { group -> monthly(group.sortedBy { it.date }, today) }
            .sortedBy { it.nextDate }

    private fun monthly(history: List<PastPayment>, today: LocalDate): RecurringPayment? {
        // One charge per month: a second payment to the same merchant in a month (a refund re-charge, a top-up) is noise.
        val byMonth = history.groupBy { it.date.withDayOfMonth(1) }.values.map { month -> month.maxBy { it.amountMinor } }
        if (byMonth.size < MIN_OCCURRENCES) return null
        if (byMonth.size < history.size / 2) return null // a shop visited many times a month is not a bill
        val gaps = byMonth.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
        if (gaps.count { it in 20..40 } < gaps.size * 0.7) return null

        val recent = byMonth.takeLast(3)
        val typical = recent.map { it.amountMinor }.sorted()[recent.size / 2]
        if (recent.any { abs(it.amountMinor - typical) > typical * AMOUNT_TOLERANCE }) return null

        val last = byMonth.last()
        if (ChronoUnit.DAYS.between(last.date, today) > STALE_DAYS) return null
        var next = last.date.plusMonths(1)
        if (next.isBefore(today.minusDays(GRACE_DAYS))) return null
        if (next.isBefore(today)) next = today
        return RecurringPayment(last.merchant, typical, next, last.date, byMonth.size, last.iconKey, last.colorKey)
    }
}
