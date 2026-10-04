package com.grid.app.core.bills

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class RecurringDetectorTest {
    private val today = LocalDate.parse("2026-10-05")
    private fun p(merchant: String, amount: Long, date: String) = PastPayment(merchant, amount, LocalDate.parse(date), "bills", "amber")

    @Test fun rentAndPhoneAreForecast() {
        val history = listOf(
            p("Century 21", 111_400, "2026-07-01"), p("Century 21", 111_400, "2026-08-01"), p("Century 21", 111_400, "2026-09-01"),
            p("SFR", 2_599, "2026-07-12"), p("SFR", 2_599, "2026-08-12"), p("SFR", 2_799, "2026-09-12"),
        )
        val found = RecurringDetector.detect(history, today)
        assertThat(found.map { it.merchant }).containsExactly("Century 21", "SFR").inOrder()
        val rent = found.first()
        assertThat(rent.nextDate).isEqualTo(LocalDate.parse("2026-10-05")) // due Oct 1, a few days late: still expected, today
        assertThat(rent.amountMinor).isEqualTo(111_400)
        assertThat(found[1].nextDate).isEqualTo(LocalDate.parse("2026-10-12"))
        assertThat(found[1].amountMinor).isEqualTo(2_599)
    }

    @Test fun everydayShopsAreNotBills() {
        val coffee = (0 until 60).map { p("Starbucks", 450, LocalDate.parse("2026-07-01").plusDays(it * 1L).toString()) }
        assertThat(RecurringDetector.detect(coffee, today)).isEmpty()
    }

    @Test fun habitsAreNotBills() {
        val pizza = listOf("2026-07-02", "2026-08-02", "2026-09-02").map { PastPayment("Pizza Jara's", 890, LocalDate.parse(it), "restaurant", "orange") }
        assertThat(RecurringDetector.detect(pizza, today)).isEmpty()
    }

    @Test fun irregularAmountsAreNotForecast() {
        val history = listOf(p("Amazon", 2_000, "2026-07-03"), p("Amazon", 9_000, "2026-08-03"), p("Amazon", 3_500, "2026-09-03"))
        assertThat(RecurringDetector.detect(history, today)).isEmpty()
    }

    @Test fun stoppedPaymentsAreNotForecast() {
        val history = listOf(p("Gym", 2_999, "2026-04-10"), p("Gym", 2_999, "2026-05-10"), p("Gym", 2_999, "2026-06-10"))
        assertThat(RecurringDetector.detect(history, today)).isEmpty()
    }

    @Test fun forecastsJoinUpcomingUnlessAlreadyTracked() {
        val rent = RecurringPayment("Century 21", 111_400, today.plusDays(2), today.minusMonths(1), 3, "housing", "sand")
        val netflix = RecurringPayment("Netflix", 1_399, today.plusDays(3), today.minusMonths(1), 3, "subscriptions", "violet")
        val later = RecurringPayment("SFR", 2_599, today.plusDays(20), today.minusDays(10), 3, "bills", "amber")
        val sub = com.grid.app.core.model.Subscription(
            id = 1, name = "Netflix", amountMinor = 1_399, currency = "EUR", cycle = com.grid.app.core.model.Cycle.Monthly,
            anchor = today.plusDays(3), nextCharge = today.plusDays(3),
            category = com.grid.app.core.model.Category(1, "Subscriptions", "subscriptions", "violet", com.grid.app.core.model.CategoryKind.EXPENSE, 0),
            paymentMethodId = null, remindDaysBefore = null, autoLog = true, status = com.grid.app.core.model.SubscriptionStatus.ACTIVE, colorKey = "violet", note = null,
        )
        val items = UpcomingPlanner.upcoming(today, 7, listOf(sub), emptyList(), listOf(rent, netflix, later), "EUR")
        assertThat(items.map { it.title to it.kind }).containsExactly("Century 21" to UpcomingKind.FORECAST, "Netflix" to UpcomingKind.SUBSCRIPTION).inOrder()
    }

    @Test fun needsThreeMonths() {
        val history = listOf(p("Netflix", 1_399, "2026-08-15"), p("Netflix", 1_399, "2026-09-15"))
        assertThat(RecurringDetector.detect(history, today)).isEmpty()
    }
}
