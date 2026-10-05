package com.grid.app.core.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.Cycle
import com.grid.app.core.model.SubscriptionDraft
import com.grid.app.core.model.TransactionDraft
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
class SubscriptionFromPaymentTest {

    private lateinit var db: GridDatabase
    private val clock = FixedClock(LocalDate.parse("2026-10-05"))
    private lateinit var transactions: TransactionRepository
    private lateinit var subscriptions: SubscriptionRepository

    @Before fun setUp() {
        db = GridDatabase.inMemory(ApplicationProvider.getApplicationContext())
        transactions = TransactionRepository(db, clock, emptySet())
        subscriptions = SubscriptionRepository(db, clock, emptySet())
    }

    @After fun tearDown() = db.close()

    private suspend fun cat(icon: String) = db.categoryDao().byIconKey(icon, CategoryKind.EXPENSE)!!.id
    private fun day(d: String) = LocalDate.parse(d).atTime(12, 0).atZone(clock.zone).toInstant().toEpochMilli()
    private suspend fun pay(merchant: String, amount: Long, date: String) =
        transactions.add(TransactionDraft(TxType.EXPENSE, amount, "EUR", cat("other"), merchant = merchant, occurredAt = day(date), source = TxSource.BANK))

    @Test fun earlierPaymentsToThePayeeAtAboutThePriceAreLinked() = runTest {
        val aug = pay("Netflix", 799, "2026-08-03")
        val sep = pay("NETFLIX", 899, "2026-09-03") // price went up
        val oct = pay("Netflix", 899, "2026-10-03")
        val merch = pay("Netflix", 3_500, "2026-09-20") // not the plan: a different amount
        val other = pay("Spotify", 899, "2026-10-01")

        val ids = subscriptions.pastPaymentsLike(transactions.get(oct)!!)
        assertThat(ids).containsExactly(aug, sep, oct)

        val sub = subscriptions.add(
            SubscriptionDraft("Netflix", 899, "EUR", Cycle.Monthly, LocalDate.parse("2026-11-03"), cat("subscriptions"), null, null, false, "red", null),
        )
        subscriptions.linkPayments(sub, ids)
        for (id in listOf(aug, sep, oct)) {
            val tx = transactions.get(id)!!
            assertThat(tx.subscriptionId).isEqualTo(sub)
            assertThat(tx.category.iconKey).isEqualTo("subscriptions")
        }
        assertThat(transactions.get(merch)!!.subscriptionId).isNull()
        assertThat(transactions.get(other)!!.subscriptionId).isNull()
        // Nothing was back-charged: the next charge is in the future.
        assertThat(transactions.observeAll().first()).hasSize(5)
    }
}
