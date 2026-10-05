package com.grid.app.core.bills

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.bank.RemoteBalance
import com.grid.app.core.data.db.entities.BankAccountEntity
import com.grid.app.core.data.prefs.AppSettings
import com.grid.app.core.data.prefs.LowFundsMode
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.Cycle
import com.grid.app.core.model.Subscription
import com.grid.app.core.model.SubscriptionStatus
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.BudgetPeriods
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class LowFundsTest {

    private fun d(s: String) = LocalDate.parse(s)
    private val today = d("2026-10-05")
    private fun bill(name: String, amount: Long, date: String) =
        UpcomingItem(UpcomingKind.FORECAST, 0, name, amount, "EUR", d(date), overdue = false, colorKey = "blue", iconKey = null)

    @Test fun enoughMoneyMeansNoWarning() {
        assertThat(LowFunds.check(100_000, listOf(bill("Rent", 85_000, "2026-10-10")), 0, today, d("2026-10-31"))).isNull()
    }

    @Test fun theFirstBillThatDoesNotFitIsNamedWithTheShortfall() {
        val alert = LowFunds.check(31_000, listOf(bill("Phone", 2_000, "2026-10-08"), bill("Rent", 85_000, "2026-10-10")), 0, today, d("2026-10-31"))!!
        assertThat(alert.by).isEqualTo(d("2026-10-10"))
        assertThat(alert.firstUncovered!!.title).isEqualTo("Rent")
        assertThat(alert.shortMinor).isEqualTo(56_000)
        assertThat(alert.dueMinor).isEqualTo(87_000)
    }

    @Test fun dailySpendingCanRunTheMoneyOutBeforeAnyBill() {
        // €100 and €20 a day: gone after 5 days.
        val alert = LowFunds.check(10_000, emptyList(), 2_000, today, d("2026-10-31"))!!
        assertThat(alert.by).isEqualTo(d("2026-10-11"))
        assertThat(alert.firstUncovered).isNull()
    }

    @Test fun aBalancePrefersWhatCanBeSpentNow() {
        val picked = RemoteBalance.pick(listOf(RemoteBalance("1240.50", "EUR", "CLBD"), RemoteBalance("312.40", "EUR", "ITAV")), "EUR")!!
        assertThat(picked.minor()).isEqualTo(31_240)
        assertThat(RemoteBalance("12.00", "EUR", "CLBD", creditDebit = "DBIT").minor()).isEqualTo(-1_200)
    }

    private val subsCat = Category(1, "Subscriptions", "subscriptions", "violet", CategoryKind.EXPENSE, 0)
    private fun sub(id: Long, name: String, amount: Long, anchor: String) = Subscription(
        id, name, amount, "EUR", Cycle.Monthly, d(anchor), d(anchor), subsCat, null, null, false, SubscriptionStatus.ACTIVE, "red", null,
    )
    private fun paid(name: String, amount: Long, date: String, subId: Long? = null) = Transaction(
        id = 0, type = TxType.EXPENSE, amountMinor = amount, currency = "EUR", category = subsCat, method = null, merchant = name, note = null,
        occurredAt = d(date).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), createdAt = 0, source = TxSource.BANK, subscriptionId = subId,
    )
    private val account = BankAccountEntity(connectionId = 1, uid = "u", identificationHash = "h", currency = "EUR", enabled = true, balanceMinor = 5_000)

    @Test fun subscriptionsAlreadyPaidThisMonthAreNotCountedAgainButUnpaidOnesAre() {
        val october = BudgetPeriods.periodFor(today, 1)
        val settings = AppSettings(currency = "EUR", lowFunds = LowFundsMode.BALANCE)
        val netflix = sub(1, "Netflix", 900, "2026-08-03")
        val gym = sub(2, "Gym", 6_000, "2026-08-02") // due the 2nd, nothing paid yet
        val state = LowFundsMonitor.evaluate(
            settings, today, october, ZoneOffset.UTC, listOf(account), listOf(netflix, gym), emptyList(),
            listOf(paid("Netflix", 900, "2026-10-03", subId = 1)), goal = null,
        )!!
        assertThat(state.alert.firstUncovered!!.title).isEqualTo("Gym")
        assertThat(state.alert.by).isEqualTo(today)
        assertThat(state.alert.dueMinor).isEqualTo(6_000)
    }

    @Test fun offOrNoBalanceMeansNoWarning() {
        val october = BudgetPeriods.periodFor(today, 1)
        val gym = sub(2, "Gym", 6_000, "2026-08-02")
        assertThat(LowFundsMonitor.evaluate(AppSettings(currency = "EUR", lowFunds = LowFundsMode.OFF), today, october, ZoneOffset.UTC, listOf(account), listOf(gym), emptyList(), emptyList(), null)).isNull()
        assertThat(LowFundsMonitor.evaluate(AppSettings(currency = "EUR"), today, october, ZoneOffset.UTC, listOf(account.copy(balanceMinor = null)), listOf(gym), emptyList(), emptyList(), null)).isNull()
    }

    @Test fun budgetModeComparesBillsWithWhatIsLeftOfTheBudget() {
        val october = BudgetPeriods.periodFor(today, 1)
        val gym = sub(2, "Gym", 6_000, "2026-08-20")
        val state = LowFundsMonitor.evaluate(
            AppSettings(currency = "EUR", lowFunds = LowFundsMode.BUDGET), today, october, ZoneOffset.UTC, emptyList(), listOf(gym), emptyList(),
            listOf(paid("Lidl", 46_000, "2026-10-02")), goal = 50_000,
        )!!
        assertThat(state.alert.shortMinor).isEqualTo(2_000)
        assertThat(state.alert.by).isEqualTo(d("2026-10-20"))
    }
}
