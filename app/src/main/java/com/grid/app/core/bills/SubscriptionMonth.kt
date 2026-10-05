package com.grid.app.core.bills

import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.Subscription
import com.grid.app.core.time.BillingSchedule
import com.grid.app.core.time.BudgetPeriod
import java.time.LocalDate
import kotlin.math.abs

/** A real payment that could be a subscription's charge. */
data class SubscriptionPayment(val subscriptionId: Long?, val payee: String?, val amountMinor: Long, val date: LocalDate)

enum class ChargeState {
    /** A payment for it was found this month. */
    PAID,
    /** Charges later this month (or just now: the bank may not show it yet). */
    DUE,
    /** Its date this month has passed and no payment was found. */
    LATE,
    /** Nothing to pay this month (e.g. a yearly plan). */
    NONE,
}

data class MonthCharge(val state: ChargeState, val dueOn: LocalDate?, val paidOn: LocalDate? = null, val paidMinor: Long? = null)

/** Has this month's charge of a subscription been paid? Answered from the payments the bank actually made. */
object SubscriptionMonth {
    /** Banks book a charge a few days around its date. */
    private const val SLACK_DAYS = 4L
    /** Prices move (€7.99 → €8.99): a quarter either way is still the same charge. */
    private const val PRICE_TOLERANCE = 0.25

    fun check(sub: Subscription, period: BudgetPeriod, today: LocalDate, payments: List<SubscriptionPayment>): MonthCharge {
        val lastDay = period.endExclusive.minusDays(1)
        val dueOn = BillingSchedule.chargesThrough(sub.anchor, sub.cycle, period.start, lastDay).firstOrNull()
        val key = MerchantKey.of(sub.name)
        val paid = payments
            .filter { p -> p.subscriptionId == sub.id || (p.subscriptionId == null && key != null && p.payee?.let(MerchantKey::of) == key && closeInPrice(p.amountMinor, sub.amountMinor)) }
            .filter { p -> p.date in period || (dueOn != null && abs(p.date.toEpochDay() - dueOn.toEpochDay()) <= SLACK_DAYS) }
            .maxByOrNull { it.date }
        return when {
            paid != null -> MonthCharge(ChargeState.PAID, dueOn, paid.date, paid.amountMinor)
            dueOn == null -> MonthCharge(ChargeState.NONE, null)
            today > dueOn.plusDays(SLACK_DAYS) -> MonthCharge(ChargeState.LATE, dueOn)
            else -> MonthCharge(ChargeState.DUE, dueOn)
        }
    }

    private fun closeInPrice(paid: Long, expected: Long): Boolean = abs(paid - expected) <= expected * PRICE_TOLERANCE
}
