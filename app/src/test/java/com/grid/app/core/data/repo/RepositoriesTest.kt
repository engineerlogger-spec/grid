package com.grid.app.core.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.IncomeLine
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.TransactionDraft
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.BudgetPeriods
import com.grid.app.core.time.FixedClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RepositoriesTest {

    private lateinit var db: GridDatabase
    private val clock = FixedClock(LocalDate.parse("2026-10-14"))
    private val october = BudgetPeriods.periodFor(clock.today(), 1)
    private var ledgerChanges = 0
    private lateinit var transactions: TransactionRepository
    private lateinit var plans: PlanRepository
    private lateinit var categories: CategoryRepository

    @Before fun setUp() {
        db = GridDatabase.inMemory(ApplicationProvider.getApplicationContext())
        val listeners = setOf(LedgerListener { ledgerChanges++ })
        transactions = TransactionRepository(db, clock, listeners)
        plans = PlanRepository(db, clock, listeners)
        categories = CategoryRepository(db)
    }

    @After fun tearDown() = db.close()

    private suspend fun cat(icon: String, kind: CategoryKind = CategoryKind.EXPENSE) = db.categoryDao().byIconKey(icon, kind)!!.id

    private suspend fun draft(amount: Long, icon: String = "restaurant", note: String? = null, merchant: String? = null, at: Long = clock.millis()) =
        TransactionDraft(TxType.EXPENSE, amount, "EUR", cat(icon), note = note, merchant = merchant, occurredAt = at)

    @Test fun addedTransactionAppearsInPeriodWithCategory() = runTest {
        val id = transactions.add(draft(1250, note = "Lunch"))
        val list = transactions.observePeriod(october).first()
        assertThat(list.single().id).isEqualTo(id)
        assertThat(list.single().category.name).isEqualTo("Restaurants")
        assertThat(list.single().title).isEqualTo("Lunch")
        assertThat(ledgerChanges).isEqualTo(1)
    }

    @Test fun transactionsOutsidePeriodAreExcluded() = runTest {
        transactions.add(draft(100, at = LocalDate.parse("2026-09-30").atStartOfDay(clock.zone).toInstant().toEpochMilli()))
        assertThat(transactions.observePeriod(october).first()).isEmpty()
    }

    @Test fun deleteAndRestoreRoundTrip() = runTest {
        val id = transactions.add(draft(999, note = "Book"))
        val deleted = transactions.delete(id)!!
        assertThat(transactions.observeAll().first()).isEmpty()
        transactions.restore(deleted)
        val restored = transactions.observeAll().first().single()
        assertThat(restored.id).isEqualTo(id)
        assertThat(restored.amountMinor).isEqualTo(999)
    }

    @Test fun updateChangesFields() = runTest {
        val id = transactions.add(draft(500, note = "Taxi", icon = "transport"))
        transactions.update(id, draft(700, note = "Taxi home", icon = "transport"))
        val tx = transactions.get(id)!!
        assertThat(tx.amountMinor).isEqualTo(700)
        assertThat(tx.note).isEqualTo("Taxi home")
    }

    @Test fun suggestionsLearnRepeatedCombos() = runTest {
        transactions.add(draft(350, note = "Coffee"))
        transactions.add(draft(350, note = "Coffee"))
        transactions.add(draft(1200, note = "Lunch"))
        val suggestions = transactions.suggestions()
        assertThat(suggestions.map { it.label }).containsExactly("Coffee")
        assertThat(suggestions.single().category.name).isEqualTo("Restaurants")
    }

    @Test fun categoryUsageOrderPutsFrequentFirstThenDefaults() = runTest {
        transactions.add(draft(100, icon = "groceries"))
        transactions.add(draft(100, icon = "groceries"))
        transactions.add(draft(100, icon = "transport"))
        val order = categories.orderedByUsage(CategoryKind.EXPENSE, transactions.categoryUsage(TxType.EXPENSE))
        assertThat(order.take(3).map { it.iconKey }).containsExactly("groceries", "transport", "restaurant").inOrder()
        assertThat(order).hasSize(16)
    }

    @Test fun merchantRuleLearnedFromMerchant() = runTest {
        transactions.add(draft(450, icon = "restaurant", merchant = "STARBUCKS #1234"))
        val rule = db.merchantRuleDao().get(MerchantKey.of("Starbucks")!!)!!
        assertThat(rule.categoryId).isEqualTo(cat("restaurant"))
    }

    @Test fun checkInCreatesIncomeAndPlan() = runTest {
        assertThat(plans.observeNeedsCheckIn(october).first()).isTrue()
        plans.confirmCheckIn(
            october, currency = "EUR", goalMinor = 200000,
            lines = listOf(IncomeLine("Salary", 250000, cat("salary", CategoryKind.INCOME)), IncomeLine("Rent from flat", 40000, cat("other_income", CategoryKind.INCOME))),
        )
        assertThat(plans.observeNeedsCheckIn(october).first()).isFalse()
        val income = transactions.observePeriod(october).first()
        assertThat(income.map { it.amountMinor }).containsExactly(250000L, 40000L)
        assertThat(income.all { it.type == TxType.INCOME && it.source == TxSource.CHECKIN }).isTrue()
        assertThat(plans.observeGoal(october).first()).isEqualTo(200000)
        assertThat(plans.incomeSources().map { it.name }).containsExactly("Salary", "Rent from flat").inOrder()
    }

    @Test fun inactiveCheckInLinesAreRememberedButNotBooked() = runTest {
        plans.confirmCheckIn(october, "EUR", 1000, listOf(IncomeLine("Bonus", 5000, cat("salary", CategoryKind.INCOME), active = false)))
        assertThat(transactions.observePeriod(october).first()).isEmpty()
        assertThat(plans.incomeSources().single().active).isFalse()
    }

    @Test fun goalFallsBackToPreviousPeriod() = runTest {
        val september = BudgetPeriods.previous(october, 1)
        plans.setGoal(september, 180000)
        assertThat(plans.observeGoal(october).first()).isEqualTo(180000)
        assertThat(plans.observeNeedsCheckIn(october).first()).isTrue()
    }

    @Test fun goalCarriesOverWhenPayDayMovesEarlier() = runTest {
        plans.setGoal(october, 200000)                           // plan starts Oct 1
        val payDayPeriod = BudgetPeriods.periodFor(clock.today(), 25) // Sep 25 – Oct 25
        assertThat(plans.observeGoal(payDayPeriod).first()).isEqualTo(200000)
        assertThat(plans.goalFor(payDayPeriod)).isEqualTo(200000)
    }

    @Test fun checkInDoesNotBookTheSameIncomeTwiceInOnePeriod() = runTest {
        val salary = cat("salary", CategoryKind.INCOME)
        plans.confirmCheckIn(october, "EUR", 1000, listOf(IncomeLine("Salary", 250000, salary)))
        // Pay day moved to the 25th → new overlapping period → check-in again.
        val payDayPeriod = BudgetPeriods.periodFor(clock.today(), 25)
        plans.confirmCheckIn(payDayPeriod, "EUR", 1000, listOf(IncomeLine("salary ", 250000, salary), IncomeLine("Bonus", 30000, salary)))
        val income = transactions.observePeriod(payDayPeriod).first().filter { it.type == TxType.INCOME }
        assertThat(income.map { it.note }).containsExactly("Salary", "Bonus")
    }

    @Test fun setGoalKeepsCheckInState() = runTest {
        plans.confirmCheckIn(october, "EUR", 1000, emptyList())
        plans.setGoal(october, 2000)
        assertThat(plans.observeGoal(october).first()).isEqualTo(2000)
        assertThat(plans.observeNeedsCheckIn(october).first()).isFalse()
    }
}
