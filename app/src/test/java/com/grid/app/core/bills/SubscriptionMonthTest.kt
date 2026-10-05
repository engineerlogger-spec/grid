package com.grid.app.core.bills

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.Cycle
import com.grid.app.core.model.CycleUnit
import com.grid.app.core.model.Subscription
import com.grid.app.core.model.SubscriptionStatus
import com.grid.app.core.time.BudgetPeriods
import org.junit.Test
import java.time.LocalDate

class SubscriptionMonthTest {

    private fun d(s: String) = LocalDate.parse(s)
    private val october = BudgetPeriods.periodFor(d("2026-10-10"), 1)
    private val subs = Category(1, "Subscriptions", "subscriptions", "violet", CategoryKind.EXPENSE, 0)

    private fun sub(id: Long, name: String, amount: Long, anchor: String, cycle: Cycle = Cycle.Monthly) = Subscription(
        id = id, name = name, amountMinor = amount, currency = "EUR", cycle = cycle, anchor = d(anchor), nextCharge = d(anchor),
        category = subs, paymentMethodId = null, remindDaysBefore = null, autoLog = false, status = SubscriptionStatus.ACTIVE,
        colorKey = "red", note = null,
    )

    @Test fun aLinkedPaymentThisMonthMeansPaid() {
        val netflix = sub(1, "Netflix", 899, "2026-08-03")
        val charge = SubscriptionMonth.check(netflix, october, d("2026-10-05"), listOf(SubscriptionPayment(1, "Netflix", 899, d("2026-10-03"))))
        assertThat(charge.state).isEqualTo(ChargeState.PAID)
        assertThat(charge.paidOn).isEqualTo(d("2026-10-03"))
    }

    @Test fun anUnlinkedPaymentToThePayeeAtAboutThePriceCountsToo() {
        val spotify = sub(2, "Spotify", 1099, "2026-01-02")
        val payments = listOf(SubscriptionPayment(null, "SPOTIFY", 1199, d("2026-10-02")))
        assertThat(SubscriptionMonth.check(spotify, october, d("2026-10-05"), payments).state).isEqualTo(ChargeState.PAID)
        // A different purchase from the same shop is not the plan.
        val other = listOf(SubscriptionPayment(null, "Spotify", 5_000, d("2026-10-02")))
        assertThat(SubscriptionMonth.check(spotify, october, d("2026-10-05"), other).state).isEqualTo(ChargeState.DUE)
    }

    @Test fun aChargeBookedJustBeforeTheMonthStillCounts() {
        val rent = sub(3, "Rent", 85_000, "2026-01-01")
        val payments = listOf(SubscriptionPayment(3, "Agence du Cedre", 85_000, d("2026-09-29")))
        assertThat(SubscriptionMonth.check(rent, october, d("2026-10-05"), payments).state).isEqualTo(ChargeState.PAID)
    }

    @Test fun dueLaterOrLateOrNotThisMonth() {
        val gym = sub(4, "Gym", 3_000, "2026-01-20")
        assertThat(SubscriptionMonth.check(gym, october, d("2026-10-05"), emptyList()).state).isEqualTo(ChargeState.DUE)
        assertThat(SubscriptionMonth.check(gym, october, d("2026-10-28"), emptyList()).state).isEqualTo(ChargeState.LATE)
        val domain = sub(5, "Domain", 1_500, "2026-03-12", Cycle(CycleUnit.YEAR, 1))
        assertThat(SubscriptionMonth.check(domain, october, d("2026-10-05"), emptyList()).state).isEqualTo(ChargeState.NONE)
    }
}
