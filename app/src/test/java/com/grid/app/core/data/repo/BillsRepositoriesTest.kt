package com.grid.app.core.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.Cycle
import com.grid.app.core.model.PendingDirection
import com.grid.app.core.model.PendingDraft
import com.grid.app.core.model.PendingStatus
import com.grid.app.core.model.SubscriptionDraft
import com.grid.app.core.model.SubscriptionStatus
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.FixedClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class BillsRepositoriesTest {

    private lateinit var db: GridDatabase
    private val today = LocalDate.parse("2026-10-04")
    private val clock = FixedClock(today)
    private lateinit var subs: SubscriptionRepository
    private lateinit var pendings: PendingRepository
    private lateinit var transactions: TransactionRepository

    @Before fun setUp() {
        db = GridDatabase.inMemory(ApplicationProvider.getApplicationContext())
        subs = SubscriptionRepository(db, clock, emptySet())
        pendings = PendingRepository(db, clock, emptySet())
        transactions = TransactionRepository(db, clock, emptySet())
    }

    @After fun tearDown() = db.close()

    private suspend fun cat(icon: String, kind: CategoryKind = CategoryKind.EXPENSE) = db.categoryDao().byIconKey(icon, kind)!!.id

    private suspend fun netflix(next: String, autoLog: Boolean = true) = SubscriptionDraft(
        name = "Netflix", amountMinor = 1399, currency = "EUR", cycle = Cycle.Monthly,
        nextCharge = LocalDate.parse(next), categoryId = cat("subscriptions"), autoLog = autoLog, colorKey = "red",
    )

    @Test fun aDueChargeAddsNothingToTheLedgerAndAdvances() = runTest {
        val id = subs.add(netflix("2026-10-04"))
        subs.processDueCharges(today)
        assertThat(transactions.observeAll().first()).isEmpty()
        assertThat(subs.get(id)!!.nextCharge).isEqualTo(LocalDate.parse("2026-11-04"))
    }

    @Test fun missedChargesAreNotBackfilled() = runTest {
        val id = subs.add(netflix("2026-07-31"))
        assertThat(transactions.observeAll().first()).isEmpty()
        assertThat(subs.get(id)!!.nextCharge).isEqualTo(LocalDate.parse("2026-10-31"))
    }

    @Test fun entriesOlderVersionsAutoLoggedAreRemovedUnlessABankPaymentBacksThem() = runTest {
        val id = subs.add(netflix("2026-11-04"))
        val logged = transactions.add(
            com.grid.app.core.model.TransactionDraft(
                type = TxType.EXPENSE, amountMinor = 1399, currency = "EUR", categoryId = cat("subscriptions"),
                merchant = "Netflix", occurredAt = clock.millis(), source = TxSource.SUBSCRIPTION, subscriptionId = id,
            ),
        )
        val bankPaid = transactions.add(
            com.grid.app.core.model.TransactionDraft(
                type = TxType.EXPENSE, amountMinor = 1399, currency = "EUR", categoryId = cat("subscriptions"),
                merchant = "Netflix", occurredAt = clock.millis(), source = TxSource.BANK, subscriptionId = id,
            ),
        )
        subs.processDueCharges(today)
        assertThat(transactions.get(logged)).isNull()
        assertThat(transactions.get(bankPaid)).isNotNull()
    }

    @Test fun pausedIsNotAdvancedAndResumeDoesNotBackCharge() = runTest {
        val id = subs.add(netflix("2026-08-15"))
        subs.setStatus(id, SubscriptionStatus.PAUSED)
        subs.processDueCharges(today)
        subs.setStatus(id, SubscriptionStatus.ACTIVE)
        assertThat(subs.get(id)!!.nextCharge).isEqualTo(LocalDate.parse("2026-10-15"))
        assertThat(transactions.observeAll().first()).isEmpty()
    }

    @Test fun editKeepsMonthEndAnchorWhenScheduleUnchanged() = runTest {
        val id = subs.add(netflix("2026-01-31"))
        subs.processDueCharges(LocalDate.parse("2026-02-01")) // logs Jan 31 → next Feb 28
        val current = subs.get(id)!!
        subs.update(id, netflix(current.nextCharge.toString()).copy(amountMinor = 1599))
        val updated = subs.get(id)!!
        assertThat(updated.amountMinor).isEqualTo(1599)
        assertThat(updated.anchor).isEqualTo(LocalDate.parse("2026-01-31"))
    }

    @Test fun settleIOweCreatesExpenseAndReopenRemovesIt() = runTest {
        val id = pendings.add(PendingDraft("Rent", "Landlord", PendingDirection.I_OWE, 85000, "EUR", due = today.plusDays(3)))
        pendings.settle(id)
        val settled = pendings.get(id)!!
        assertThat(settled.status).isEqualTo(PendingStatus.DONE)
        val tx = transactions.get(settled.transactionId!!)!!
        assertThat(tx.type).isEqualTo(TxType.EXPENSE)
        assertThat(tx.amountMinor).isEqualTo(85000)
        assertThat(tx.source).isEqualTo(TxSource.PENDING)
        assertThat(tx.category.iconKey).isEqualTo("other")

        pendings.reopen(id)
        assertThat(pendings.get(id)!!.status).isEqualTo(PendingStatus.PENDING)
        assertThat(transactions.observeAll().first()).isEmpty()
    }

    @Test fun settleOwedToMeCreatesIncome() = runTest {
        val id = pendings.add(PendingDraft("Dinner split", "Sam", PendingDirection.OWED_TO_ME, 2400, "EUR"))
        pendings.settle(id)
        val tx = transactions.get(pendings.get(id)!!.transactionId!!)!!
        assertThat(tx.type).isEqualTo(TxType.INCOME)
        assertThat(tx.category.kind).isEqualTo(CategoryKind.INCOME)
    }

    @Test fun observeAllResolvesCategories() = runTest {
        pendings.add(PendingDraft("Dentist", null, PendingDirection.I_OWE, 6000, "EUR", due = today, categoryId = cat("health")))
        assertThat(pendings.observeAll().first().single().category?.name).isEqualTo("Health")
        subs.add(netflix("2026-10-20"))
        assertThat(subs.observeAll().first().single().category.name).isEqualTo("Subscriptions")
    }
}
