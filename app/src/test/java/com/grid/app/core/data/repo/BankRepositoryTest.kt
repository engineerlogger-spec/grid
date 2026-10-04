package com.grid.app.core.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.bank.Aspsp
import com.grid.app.core.bank.BankSession
import com.grid.app.core.bank.RemoteAccount
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.model.BankStatus
import com.grid.app.core.model.BankTxKind
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.TransactionDraft
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.FixedClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class BankRepositoryTest {

    private lateinit var db: GridDatabase
    private val clock = FixedClock(LocalDate.parse("2026-10-04"))
    private lateinit var transactions: TransactionRepository
    private lateinit var bank: BankRepository
    private val revolut = Aspsp("Revolut", "FR", 15_552_000)

    @Before fun setUp() {
        db = GridDatabase.inMemory(ApplicationProvider.getApplicationContext())
        transactions = TransactionRepository(db, clock, emptySet())
        bank = BankRepository(db, transactions, clock)
    }

    @After fun tearDown() = db.close()

    private suspend fun cat(icon: String, kind: CategoryKind = CategoryKind.EXPENSE) = db.categoryDao().byIconKey(icon, kind)!!.id

    private fun session(vararg accounts: RemoteAccount) = BankSession("s-1", clock.millis() + 86_400_000L * 180, accounts.toList())

    private suspend fun connected(): Long {
        bank.beginAuth(revolut, "st", null)
        return bank.completeAuth("st", session(RemoteAccount("u-1", "h-1", "EUR", "Main", "LT1")), "EUR").accounts.single().id
    }

    private suspend fun stage(accountId: Long, ext: String, amount: Long, counterparty: String, direction: CaptureDirection, kind: BankTxKind) =
        db.bankDao().insertStaged(
            BankTransactionEntity(
                accountId = accountId, externalId = ext, bookingEpochDay = 20_000, occurredAt = clock.millis(), amountMinor = amount,
                currency = "EUR", direction = direction, kind = kind, counterparty = counterparty, counterpartyKey = MerchantKey.of(counterparty),
                state = BankTxState.NEEDS_DECISION, rawJson = "{\"id\":\"$ext\"}", createdAt = clock.millis(),
            ),
        )

    @Test fun authorisationWithAnotherStateIsRefused() = runTest {
        bank.beginAuth(revolut, "expected", null)
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { bank.completeAuth("forged", session(), "EUR") }
        }
    }

    @Test fun firstConnectEnablesOnlyAppCurrencyAccounts() = runTest {
        bank.beginAuth(revolut, "st", LocalDate.parse("2026-07-01").toEpochDay())
        val result = bank.completeAuth("st", session(RemoteAccount("u-1", "h-1", "EUR", "Main", "LT1"), RemoteAccount("u-2", "h-2", "USD", "Dollars", null)), "EUR")
        assertThat(result.firstConnect).isTrue()
        assertThat(result.accounts.associate { it.currency to it.enabled }).containsExactly("EUR", true, "USD", false)
        val c = bank.connection()!!
        assertThat(c.status).isEqualTo(BankStatus.ACTIVE)
        assertThat(c.sessionId).isEqualTo("s-1")
        assertThat(c.authState).isNull()
    }

    @Test fun reconnectKeepsChoicesAndSwapsUids() = runTest {
        val id = connected()
        bank.setEnabled(id, false)
        bank.disconnect()
        assertThat(bank.connection()!!.status).isEqualTo(BankStatus.NEEDS_SETUP)

        bank.beginAuth(revolut, "st2", null)
        val again = bank.completeAuth("st2", session(RemoteAccount("u-NEW", "h-1", "EUR", null, null)), "EUR")
        assertThat(again.firstConnect).isFalse()
        val account = again.accounts.single()
        assertThat(account.id).isEqualTo(id)
        assertThat(account.uid).isEqualTo("u-NEW")
        assertThat(account.enabled).isFalse()
        assertThat(account.iban).isEqualTo("LT1")
    }

    @Test fun groupsReviewItems() = runTest {
        val account = connected()
        repeat(2) {
            transactions.add(TransactionDraft(TxType.EXPENSE, 2340, "EUR", cat("other"), merchant = "Lidl", occurredAt = clock.millis() - it, source = TxSource.BANK, needsReview = true))
        }
        stage(account, "t1", 85_000, "J. Dupont", CaptureDirection.OUT, BankTxKind.TRANSFER_OUT)
        stage(account, "t2", 85_000, "J. Dupont", CaptureDirection.OUT, BankTxKind.TRANSFER_OUT)
        stage(account, "t3", 250_000, "ACME SAS", CaptureDirection.IN, BankTxKind.MONEY_IN)

        val groups = bank.observeReviewGroups().first().associateBy { it.title }
        assertThat(groups.keys).containsExactly("Lidl", "J. Dupont", "Acme SAS")
        assertThat(groups["Lidl"]!!.kind).isEqualTo(ReviewKind.CATEGORISE)
        assertThat(groups["Lidl"]!!.totalMinor).isEqualTo(4680)
        assertThat(groups["J. Dupont"]!!.count).isEqualTo(2)
        assertThat(groups["J. Dupont"]!!.kind).isEqualTo(ReviewKind.DECIDE_OUT)
        assertThat(groups["Acme SAS"]!!.kind).isEqualTo(ReviewKind.DECIDE_IN)
    }

    @Test fun categoriseClearsReviewAndLearnsTheMerchant() = runTest {
        connected()
        transactions.add(TransactionDraft(TxType.EXPENSE, 2340, "EUR", cat("other"), merchant = "Lidl", occurredAt = clock.millis(), source = TxSource.BANK, needsReview = true))
        bank.categorise(bank.observeReviewGroups().first().single(), cat("groceries"))
        val tx = transactions.observeAll().first().single()
        assertThat(tx.needsReview).isFalse()
        assertThat(tx.category.iconKey).isEqualTo("groceries")
        assertThat(db.merchantRuleDao().get("lidl")!!.categoryId).isEqualTo(cat("groceries"))
        assertThat(bank.observeReviewGroups().first()).isEmpty()
    }

    @Test fun bookingCreatesLinkedTransactionsAndLearnsTheCounterparty() = runTest {
        val account = connected()
        stage(account, "t1", 85_000, "J. Dupont", CaptureDirection.OUT, BankTxKind.TRANSFER_OUT)
        stage(account, "t2", 250_000, "ACME SAS", CaptureDirection.IN, BankTxKind.MONEY_IN)
        val groups = bank.observeReviewGroups().first().associateBy { it.title }
        bank.book(groups["J. Dupont"]!!, cat("housing"))
        bank.book(groups["Acme SAS"]!!, cat("salary", CategoryKind.INCOME))

        val txs = transactions.observeAll().first().associateBy { it.merchant }
        assertThat(txs["J. Dupont"]!!.type).isEqualTo(TxType.EXPENSE)
        assertThat(txs["J. Dupont"]!!.source).isEqualTo(TxSource.BANK)
        assertThat(txs["Acme SAS"]!!.type).isEqualTo(TxType.INCOME)
        assertThat(db.bankDao().stagedLinkedTo(txs["J. Dupont"]!!.id)!!.state).isEqualTo(BankTxState.BOOKED)
        assertThat(db.merchantRuleDao().get("j dupont")!!.categoryId).isEqualTo(cat("housing"))
        assertThat(bank.observeReviewGroups().first()).isEmpty()
    }

    @Test fun bookingTheSalaryReplacesTheCheckInEstimate() = runTest {
        val account = connected()
        val salary = cat("salary", CategoryKind.INCOME)
        val estimate = transactions.add(
            TransactionDraft(TxType.INCOME, 250_000, "EUR", salary, note = "Salary", occurredAt = clock.millis() - 3 * 86_400_000L, source = TxSource.CHECKIN),
        )
        stage(account, "s1", 187_500, "ACME SAS", CaptureDirection.IN, BankTxKind.MONEY_IN)
        bank.book(bank.observeReviewGroups().first().single(), salary)

        val income = transactions.observeAll().first().single()
        assertThat(income.id).isEqualTo(estimate)
        assertThat(income.amountMinor).isEqualTo(187_500)
        assertThat(income.merchant).isEqualTo("Acme SAS")
        assertThat(db.merchantRuleDao().get("acme sas")!!.categoryId).isEqualTo(salary)
        assertThat(db.bankDao().stagedLinkedTo(estimate)!!.state).isEqualTo(BankTxState.BOOKED)
    }

    @Test fun myOwnAccountTracksTransfersAsMovedAndLearns() = runTest {
        val account = connected()
        stage(account, "t1", 150_000, "SAM TAYLOR", CaptureDirection.IN, BankTxKind.MONEY_IN)
        bank.markOwnAccount(bank.observeReviewGroups().first().single())
        assertThat(db.bankDao().ownAccountRule("sam taylor")).isNotNull()
        assertThat(bank.observeReviewGroups().first()).isEmpty()
        assertThat(transactions.observeAll().first()).isEmpty() // not income

        val moved = bank.observeOwnTransfers().first().single()
        assertThat(moved.amountMinor).isEqualTo(150_000)
        assertThat(moved.incoming).isTrue()
        assertThat(moved.counterparty).isEqualTo("Sam Taylor")
        assertThat(moved.countIn).isNull()

        bank.countIn(moved.id, LocalDate.parse("2026-11-01"))
        assertThat(bank.observeOwnTransfers().first().single().countIn).isEqualTo(LocalDate.parse("2026-11-01"))
        bank.countIn(moved.id, null)
        assertThat(bank.observeOwnTransfers().first().single().countIn).isNull()
    }

    @Test fun ignoreIsJustThisOnce() = runTest {
        val account = connected()
        stage(account, "t1", 5_000, "Someone", CaptureDirection.OUT, BankTxKind.TRANSFER_OUT)
        bank.ignore(bank.observeReviewGroups().first().single())
        assertThat(db.bankDao().ownAccountRule("someone")).isNull()
        assertThat(bank.observeReviewGroups().first()).isEmpty()
        assertThat(bank.recentRaw()).contains("\"t1\"")
    }
}
