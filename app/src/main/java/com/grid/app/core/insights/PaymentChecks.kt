package com.grid.app.core.insights

import kotlin.math.abs

/** A payment out as the bank listed it. [timeKnown]: the bank gave its real time, not only the day. */
data class PaymentFacts(val rowId: Long, val amountMinor: Long, val occurredAt: Long, val timeKnown: Boolean)

/** Something worth saying about a payment, beyond what's left this month. */
sealed interface PaymentFinding {
    /** A bill was paid; [usualMinor] when this charge differs from the usual one by 5% or more. */
    data class BillPaid(val name: String, val usualMinor: Long?) : PaymentFinding

    /** Far above what this payee usually takes ([usualMinor]: the median of the earlier payments). */
    data class BiggerThanUsual(val usualMinor: Long) : PaymentFinding

    /** The same amount to the same payee minutes apart ([otherAt]: when the other one was). */
    data class ChargedTwice(val otherAt: Long) : PaymentFinding
}

/** The rules behind the check after paying (spec 2026-10-06 live sync, §6). Pure: easy to test. */
object PaymentChecks {
    private const val TWICE_WINDOW_MS = 10 * 60_000L
    /** €1 car-wash tokens or coffee refills are repeated on purpose. */
    private const val TWICE_FLOOR_MINOR = 500L
    private const val BILL_DIFFERENCE = 0.05
    private const val BIGGER_FACTOR = 1.5
    private const val BIGGER_GAP_MINOR = 1_000L
    private const val BIGGER_HISTORY = 3

    /** The most important finding: a double charge, then a bill, then an unusual amount. */
    fun findings(payment: PaymentFacts, bill: Pair<String, Long>?, earlierToPayee: List<Long>, samePayeeNear: List<PaymentFacts>): PaymentFinding? =
        chargedTwice(payment, samePayeeNear) ?: billPaid(payment, bill) ?: biggerThanUsual(payment, earlierToPayee)

    fun chargedTwice(payment: PaymentFacts, samePayeeNear: List<PaymentFacts>): PaymentFinding.ChargedTwice? {
        if (payment.amountMinor < TWICE_FLOOR_MINOR || !payment.timeKnown) return null
        return samePayeeNear.firstOrNull {
            it.rowId != payment.rowId && it.timeKnown && it.amountMinor == payment.amountMinor && abs(it.occurredAt - payment.occurredAt) <= TWICE_WINDOW_MS
        }?.let { PaymentFinding.ChargedTwice(it.occurredAt) }
    }

    /** [bill]: the subscription's name and usual amount. */
    fun billPaid(payment: PaymentFacts, bill: Pair<String, Long>?): PaymentFinding.BillPaid? = bill?.let { (name, usual) ->
        PaymentFinding.BillPaid(name, usual.takeIf { it > 0 && abs(payment.amountMinor - it).toDouble() / it >= BILL_DIFFERENCE })
    }

    fun biggerThanUsual(payment: PaymentFacts, earlierToPayee: List<Long>): PaymentFinding.BiggerThanUsual? {
        if (earlierToPayee.size < BIGGER_HISTORY) return null
        val median = earlierToPayee.sorted()[earlierToPayee.size / 2]
        return if (payment.amountMinor > median * BIGGER_FACTOR && payment.amountMinor - median >= BIGGER_GAP_MINOR) PaymentFinding.BiggerThanUsual(median) else null
    }
}
