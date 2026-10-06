package com.grid.app.core.bills

import com.grid.app.core.bank.MatchRules
import com.grid.app.core.data.db.entities.SubscriptionEntity
import com.grid.app.core.model.CycleUnit
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.SubscriptionStatus
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.min

/** A bill found in the payments, as compared with what the user already tracks. */
data class FoundBillFacts(
    val payeeKey: String?,
    val name: String,
    val amountMinor: Long,
    val cycleUnit: CycleUnit,
    val cycleCount: Int,
    val nextCharge: LocalDate,
    val varies: Boolean,
)

/**
 * Is a bill Gemini found one the user already tracks? Their own names rarely match the bank's ("Loyer" vs
 * "J. Dupont", "Forfait mobile" vs "SFR"), so besides the payee and the name it looks at the money: a subscription
 * already holding this payee's payments, or one on the same schedule for about the same amount on the same day.
 */
object SuggestionMatch {
    /** A day of month within this many days (either side, across month ends) is the same charge day. */
    private const val DAY_SLACK = 4
    private const val PRICE_TOLERANCE = 0.15
    private const val VARYING_TOLERANCE = 0.40

    /** [payeeLinkedTo]: the subscriptions this payee's past payments are already linked to. */
    fun isTracked(tracked: SubscriptionEntity, found: FoundBillFacts, payeeLinkedTo: Set<Long>): Boolean {
        if (tracked.status == SubscriptionStatus.SUGGESTED) return false
        if (found.payeeKey != null && tracked.payeeKey == found.payeeKey) return true
        if (tracked.id in payeeLinkedTo) return true
        val trackedKey = MerchantKey.of(tracked.name)
        if (trackedKey != null && (trackedKey == found.payeeKey || trackedKey == MerchantKey.of(found.name) || MatchRules.similar(tracked.name, found.name))) return true
        return sameSchedule(tracked, found) && closeInPrice(tracked, found)
    }

    private fun sameSchedule(t: SubscriptionEntity, f: FoundBillFacts): Boolean {
        if (t.cycleUnit != f.cycleUnit || t.cycleCount != f.cycleCount) return false
        val trackedDay = LocalDate.ofEpochDay(t.nextChargeEpochDay)
        return when (f.cycleUnit) {
            CycleUnit.WEEK -> abs(trackedDay.dayOfWeek.value - f.nextCharge.dayOfWeek.value).let { min(it, 7 - it) } <= 1
            CycleUnit.MONTH -> dayOfMonthGap(trackedDay.dayOfMonth, f.nextCharge.dayOfMonth) <= DAY_SLACK
            CycleUnit.YEAR -> trackedDay.month == f.nextCharge.month && dayOfMonthGap(trackedDay.dayOfMonth, f.nextCharge.dayOfMonth) <= DAY_SLACK
        }
    }

    private fun closeInPrice(t: SubscriptionEntity, f: FoundBillFacts): Boolean {
        val tolerance = if (t.amountVaries || f.varies) VARYING_TOLERANCE else PRICE_TOLERANCE
        return abs(t.amountMinor - f.amountMinor) <= maxOf(t.amountMinor, f.amountMinor) * tolerance
    }

    /** Days between two days of month, going round the month end (the 30th and the 2nd are 3 days apart). */
    private fun dayOfMonthGap(a: Int, b: Int): Int = abs(a - b).let { min(it, 31 - it) }
}
