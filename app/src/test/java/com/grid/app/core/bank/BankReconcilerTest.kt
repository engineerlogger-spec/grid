package com.grid.app.core.bank

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.OwnAccountRuleEntity
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.PendingRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.SubscriptionRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.BankTxKind
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.Cycle
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.PaymentKind
import com.grid.app.core.model.PendingDirection
import com.grid.app.core.model.PendingDraft
import com.grid.app.core.model.PendingStatus
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
class BankReconcilerTest {

    private lateinit var db: GridDatabase
    private val today = LocalDate.parse("2026-10-04")
    private val clock = FixedClock(today)
    private val now = clock.millis()
    private val hour = 3_600_000L
    private val day = 24 * hour
    private lateinit var transactions: TransactionRepository
    private lateinit var pendings: PendingRepository
    private lateinit var subs: SubscriptionRepository
    private lateinit var reconciler: BankReconciler
    private var accountId = 0L
    private var ext = 0

    @Before fun setUp() = kotlinx.coroutines.runBlocking {
        db = GridDatabase.inMemory(ApplicationProvider.getApplicationContext())
        transactions = TransactionRepository(db, clock, emptySet())
        pendings = PendingRepository(db, clock, emptySet())
        subs = SubscriptionRepository(db, clock, emptySet())
        reconciler = BankReconciler(db, transactions, pendings, CategoryRepository(db), clock)
        val bank = BankRepository(db, transactions, clock)
        bank.beginAuth(Aspsp("Revolut", "FR", null), "st", null)
        accountId = bank.completeAuth("st", BankSession("s", null, listOf(RemoteAccount("u", "h", "EUR", null, "LT1"))), "EUR").accounts.single().id
    }

    @After fun tearDown() = db.close()

    private suspend fun cat(icon: String, kind: CategoryKind = CategoryKind.EXPENSE) = db.categoryDao().byIconKey(icon, kind)!!.id
    private suspend fun method(kind: PaymentKind) = db.paymentMethodDao().all().first { it.kind == kind }.id

    /** Stages a bank row the way BankSync does, then reconciles it. */
    private suspend fun bankRow(
        amount: Long, counterparty: String, kind: BankTxKind = BankTxKind.CARD_SPEND, at: Long = now,
        currency: String = "EUR", mcc: String? = null, via: PaymentKind? = null,
    ): Pair<BankTxState, BankTransactionEntity> {
        val direction = if (kind in setOf(BankTxKind.MONEY_IN, BankTxKind.REFUND, BankTxKind.TOP_UP)) CaptureDirection.IN else CaptureDirection.OUT
        val cleaned = DescriptorCleaner.clean(counterparty)
        val row = BankTransactionEntity(
            accountId = accountId, externalId = "x${ext++}", bookingEpochDay = today.toEpochDay(), occurredAt = at, amountMinor = amount,
            currency = currency, direction = direction, kind = kind, counterparty = counterparty,
            counterpartyKey = cleaned?.merchant?.let(MerchantKey::of), mcc = mcc, via = via ?: cleaned?.via,
            state = BankTxState.NEEDS_DECISION, rawJson = "{}", createdAt = now,
        )
        val id = db.bankDao().insertStaged(row)
        val state = reconciler.process(row.copy(id = id), "EUR")
        return state to db.bankDao().staged(id)!!
    }

    private suspend fun ledger() = transactions.observeAll().first()

    @Test fun walletCaptureIsMergedKeepingItsMethodAndTakingTheBankAmount() = runTest {
        val captured = transactions.add(
            TransactionDraft(TxType.EXPENSE, 1000, "EUR", cat("restaurant"), method(PaymentKind.GOOGLE_WALLET), "Le Comptoir", occurredAt = now - 2 * day, source = TxSource.CAPTURE),
        )
        val (state, row) = bankRow(1050, "LE COMPTOIR", at = now - day)
        assertThat(state).isEqualTo(BankTxState.BOOKED)
        assertThat(row.transactionId).isEqualTo(captured)
        val tx = ledger().single()
        assertThat(tx.amountMinor).isEqualTo(1050)
        assertThat(tx.method?.kind).isEqualTo(PaymentKind.GOOGLE_WALLET)
    }

    @Test fun paypalPurchaseRelabelsARevolutCapture() = runTest {
        transactions.add(TransactionDraft(TxType.EXPENSE, 1349, "EUR", cat("subscriptions"), method(PaymentKind.REVOLUT), "Netflix", occurredAt = now, source = TxSource.CAPTURE))
        bankRow(1349, "PAYPAL *NETFLIX")
        assertThat(ledger().single().method?.kind).isEqualTo(PaymentKind.PAYPAL)
    }

    @Test fun manualDuplicateIsMergedAndOnlyOneOfTwoCoffeesIsTaken() = runTest {
        transactions.add(TransactionDraft(TxType.EXPENSE, 350, "EUR", cat("restaurant"), merchant = "Coffee", occurredAt = now - 2 * hour))
        val (_, first) = bankRow(350, "COFFEE")
        val (_, second) = bankRow(350, "COFFEE")
        assertThat(first.transactionId).isNotEqualTo(second.transactionId)
        assertThat(ledger()).hasSize(2)
    }

    @Test fun loggedSubscriptionIsLinked() = runTest {
        subs.add(SubscriptionDraft("Netflix", 1349, "EUR", Cycle.Monthly, today, cat("subscriptions"), colorKey = "red"))
        val logged = ledger().single()
        val (_, row) = bankRow(1399, "PAYPAL *NETFLIX", at = now + day)
        assertThat(row.transactionId).isEqualTo(logged.id)
        assertThat(ledger().single().amountMinor).isEqualTo(1399)
    }

    @Test fun rentTransferBooksItsBillAndTheBillIsNotLoggedAgain() = runTest {
        val rentId = subs.add(SubscriptionDraft("Rent", 85_000, "EUR", Cycle.Monthly, today.plusDays(2), cat("housing"), autoLog = true, colorKey = "sand"))
        val (state, row) = bankRow(85_000, "J. Dupont", kind = BankTxKind.TRANSFER_OUT)
        assertThat(state).isEqualTo(BankTxState.BOOKED)
        val tx = transactions.get(row.transactionId!!)!!
        assertThat(tx.subscriptionId).isEqualTo(rentId)
        assertThat(tx.category.iconKey).isEqualTo("housing")
        assertThat(subs.processDueCharges(today.plusDays(2))).isEqualTo(0)
        assertThat(ledger()).hasSize(1)
    }

    @Test fun openPendingPaymentIsSettled() = runTest {
        val id = pendings.add(PendingDraft("Dinner split", "Sam Smith", PendingDirection.OWED_TO_ME, 2400, "EUR", due = today))
        val (state, row) = bankRow(2400, "SAM SMITH", kind = BankTxKind.MONEY_IN)
        assertThat(state).isEqualTo(BankTxState.BOOKED)
        val settled = pendings.get(id)!!
        assertThat(settled.status).isEqualTo(PendingStatus.DONE)
        assertThat(settled.transactionId).isEqualTo(row.transactionId)
    }

    @Test fun movesInsideRevolutAreIgnoredAndOwnAccountsAreTrackedAsMoved() = runTest {
        assertThat(bankRow(20_000, "To EUR Vault", kind = BankTxKind.INTERNAL).first).isEqualTo(BankTxState.IGNORED)
        db.bankDao().insertOwnAccountRule(OwnAccountRuleEntity("sam taylor", now))
        assertThat(bankRow(150_000, "SAM TAYLOR", kind = BankTxKind.MONEY_IN).first).isEqualTo(BankTxState.OWN_TRANSFER)
        assertThat(bankRow(20_000, "Sam Taylor", kind = BankTxKind.TRANSFER_OUT).first).isEqualTo(BankTxState.OWN_TRANSFER)
        assertThat(bankRow(10_000, "Top-Up by *4421", kind = BankTxKind.TOP_UP).first).isEqualTo(BankTxState.OWN_TRANSFER)
        assertThat(ledger()).isEmpty() // neither income nor spending
    }

    @Test fun unknownCardPaymentIsCountedUnderOtherWithoutTeachingARule() = runTest {
        val (state, row) = bankRow(2340, "BERFIN 1234")
        assertThat(state).isEqualTo(BankTxState.BOOKED)
        val tx = transactions.get(row.transactionId!!)!!
        assertThat(tx.merchant).isEqualTo("Berfin")
        assertThat(tx.category.iconKey).isEqualTo("other")
        assertThat(tx.needsReview).isTrue()
        assertThat(tx.source).isEqualTo(TxSource.BANK)
        assertThat(tx.method?.kind).isEqualTo(PaymentKind.REVOLUT)
        assertThat(db.merchantRuleDao().get("berfin")).isNull()
    }

    @Test fun knownMerchantsAndBankCodesAreCategorisedAutomatically() = runTest {
        assertThat(transactions.get(bankRow(2340, "LIDL 1234").second.transactionId!!)!!.category.iconKey).isEqualTo("groceries")
        val sfr = transactions.get(bankRow(2599, "SFR", kind = BankTxKind.TRANSFER_OUT).second.transactionId!!)!!
        assertThat(sfr.category.iconKey).isEqualTo("bills")
        assertThat(sfr.needsReview).isFalse()
        // Cash and Insurance didn't exist on older installs: created on first need.
        assertThat(transactions.get(bankRow(10_000, "Cash at Lcl", kind = BankTxKind.CASH_WITHDRAWAL).second.transactionId!!)!!.category.name).isEqualTo("Cash")
        assertThat(transactions.get(bankRow(3_534, "Allianz Direct Vers.", kind = BankTxKind.TRANSFER_OUT).second.transactionId!!)!!.category.name).isEqualTo("Insurance")
        val refund = transactions.get(bankRow(2_228, "Some shop", kind = BankTxKind.REFUND).second.transactionId!!)!!
        assertThat(refund.type).isEqualTo(TxType.INCOME)
        assertThat(refund.category.iconKey).isEqualTo("refund")
    }

    @Test fun theHoldersOwnMoneyIsAMoveNotIncome() = runTest {
        // The account is named after its holder (Revolut does this); setUp's account has no name, so add one.
        db.bankDao().updateAccount(db.bankDao().account(accountId)!!.copy(name = "Abdelhamid Mouloud"))
        assertThat(bankRow(150_000, "MOULOUD ABDELHAMID", kind = BankTxKind.TOP_UP).first).isEqualTo(BankTxState.OWN_TRANSFER)
        assertThat(bankRow(50_800, "Abdelhamid N26", kind = BankTxKind.TRANSFER_OUT).first).isEqualTo(BankTxState.OWN_TRANSFER)
        // An insurer paying back through a "top-up" is a refund, not the user's own money.
        val (state, row) = bankRow(3_781, "ALLSECUR", kind = BankTxKind.TOP_UP)
        assertThat(state).isEqualTo(BankTxState.BOOKED)
        assertThat(transactions.get(row.transactionId!!)!!.category.iconKey).isEqualTo("refund")
    }

    @Test fun merchantCategoryCodeCategorisesWithoutReview() = runTest {
        val (_, row) = bankRow(2340, "CARREFOUR", mcc = "5411")
        val tx = transactions.get(row.transactionId!!)!!
        assertThat(tx.category.iconKey).isEqualTo("groceries")
        assertThat(tx.needsReview).isFalse()
    }

    @Test fun learnedMerchantIsUsed() = runTest {
        transactions.add(TransactionDraft(TxType.EXPENSE, 100, "EUR", cat("groceries"), merchant = "Lidl", occurredAt = now - 30 * day))
        val (_, row) = bankRow(2340, "LIDL 1234")
        assertThat(transactions.get(row.transactionId!!)!!.category.iconKey).isEqualTo("groceries")
    }

    @Test fun transfersToPeopleAreCountedAndFollowWhatTheUserTaught() = runTest {
        val first = bankRow(85_000, "J. Dupont", kind = BankTxKind.TRANSFER_OUT)
        assertThat(first.first).isEqualTo(BankTxState.BOOKED)
        assertThat(transactions.get(first.second.transactionId!!)!!.category.iconKey).isEqualTo("other")
        transactions.add(TransactionDraft(TxType.EXPENSE, 85_000, "EUR", cat("housing"), merchant = "J. Dupont", occurredAt = now - 40 * day, source = TxSource.BANK))
        val (state, row) = bankRow(85_000, "J. Dupont", kind = BankTxKind.TRANSFER_OUT, at = now)
        assertThat(state).isEqualTo(BankTxState.BOOKED)
        assertThat(transactions.get(row.transactionId!!)!!.category.iconKey).isEqualTo("housing")
    }

    @Test fun salaryLinksToTheCheckInIncome() = runTest {
        val checkIn = transactions.add(
            TransactionDraft(TxType.INCOME, 250_000, "EUR", cat("salary", CategoryKind.INCOME), note = "Salary", occurredAt = now - 4 * day, source = TxSource.CHECKIN),
        )
        val (state, row) = bankRow(246_300, "ACME SAS", kind = BankTxKind.MONEY_IN)
        assertThat(state).isEqualTo(BankTxState.BOOKED)
        assertThat(row.transactionId).isEqualTo(checkIn)
        assertThat(ledger().single().amountMinor).isEqualTo(246_300)
    }

    @Test fun learnedSalaryReplacesTheCheckInEstimateWhateverTheAmount() = runTest {
        val salary = cat("salary", CategoryKind.INCOME)
        // Last month's salary from ACME was booked as Salary, so the rule exists.
        transactions.add(TransactionDraft(TxType.INCOME, 240_000, "EUR", salary, merchant = "Acme SAS", occurredAt = now - 35 * day, source = TxSource.BANK))
        val estimate = transactions.add(TransactionDraft(TxType.INCOME, 250_000, "EUR", salary, note = "Salary", occurredAt = now - 3 * day, source = TxSource.CHECKIN))
        val (state, row) = bankRow(190_000, "ACME SAS", kind = BankTxKind.MONEY_IN)
        assertThat(state).isEqualTo(BankTxState.BOOKED)
        assertThat(row.transactionId).isEqualTo(estimate)
        assertThat(transactions.get(estimate)!!.amountMinor).isEqualTo(190_000)
        assertThat(ledger().filter { it.type == TxType.INCOME }).hasSize(2) // last month + this month, no duplicate
    }

    /** A row as an older version left it, with the bank's raw JSON so today's rules can re-read it. */
    private suspend fun oldRow(name: String, amount: String, code: String, credit: Boolean, state: BankTxState, txId: Long? = null): Long {
        val party = if (credit) """"debtor":{"name":"$name"}""" else """"creditor":{"name":"$name"}"""
        val raw = """{"transaction_amount":{"currency":"EUR","amount":"$amount"},"credit_debit_indicator":"${if (credit) "CRDT" else "DBIT"}",$party,"bank_transaction_code":{"code":"$code"},"status":"BOOK"}"""
        return db.bankDao().insertStaged(
            BankTransactionEntity(
                accountId = accountId, externalId = "old${ext++}", bookingEpochDay = today.toEpochDay(), occurredAt = now,
                amountMinor = (amount.toDouble() * 100).toLong(), currency = "EUR", direction = if (credit) CaptureDirection.IN else CaptureDirection.OUT,
                kind = if (credit) BankTxKind.MONEY_IN else BankTxKind.CARD_SPEND, counterparty = name,
                counterpartyKey = DescriptorCleaner.clean(name)?.merchant?.let(MerchantKey::of), state = state, transactionId = txId, rawJson = raw, createdAt = now,
            ),
        )
    }

    @Test fun dataFromOlderVersionsIsSortedWithTodaysRules() = runTest {
        db.bankDao().updateAccount(db.bankDao().account(accountId)!!.copy(name = "Abdelhamid Mouloud"))
        // An E.Leclerc payment an older version filed under Other.
        val leclerc = transactions.add(TransactionDraft(TxType.EXPENSE, 2340, "EUR", cat("other"), merchant = "E.leclerc", occurredAt = now, source = TxSource.BANK, needsReview = true))
        oldRow("E.leclerc", "23.40", "CARD_PAYMENT", credit = false, state = BankTxState.BOOKED, txId = leclerc)
        // A salary-account top-up an older version booked as income under Other.
        val topUp = transactions.add(TransactionDraft(TxType.INCOME, 150_000, "EUR", cat("other_income", CategoryKind.INCOME), merchant = "Mouloud Abdelhamid", occurredAt = now, source = TxSource.BANK, needsReview = true))
        oldRow("MOULOUD ABDELHAMID", "1500.00", "TOPUP", credit = true, state = BankTxState.BOOKED, txId = topUp)
        // An SFR transfer an older version left waiting for a decision.
        val sfr = oldRow("SFR", "25.99", "TRANSFER", credit = false, state = BankTxState.NEEDS_DECISION)

        reconciler.revisit("EUR", emptySet())

        assertThat(transactions.get(leclerc)!!.category.iconKey).isEqualTo("groceries")
        assertThat(transactions.get(leclerc)!!.needsReview).isFalse()
        assertThat(transactions.get(topUp)).isNull() // not income: money moved from the salary account
        assertThat(db.bankDao().stagedByState(BankTxState.OWN_TRANSFER).single().counterparty).isEqualTo("MOULOUD ABDELHAMID")
        val sfrRow = db.bankDao().staged(sfr)!!
        assertThat(sfrRow.state).isEqualTo(BankTxState.BOOKED)
        assertThat(transactions.get(sfrRow.transactionId!!)!!.category.iconKey).isEqualTo("bills")
    }

    @Test fun whatTheUserSortedIsNeverChanged() = runTest {
        val sorted = transactions.add(TransactionDraft(TxType.EXPENSE, 2340, "EUR", cat("health"), merchant = "E.leclerc", occurredAt = now, source = TxSource.BANK))
        oldRow("E.leclerc", "23.40", "CARD_PAYMENT", credit = false, state = BankTxState.BOOKED, txId = sorted)
        reconciler.revisit("EUR", emptySet())
        assertThat(transactions.get(sorted)!!.category.iconKey).isEqualTo("health")
    }

    @Test fun otherCurrencyWaitsForTheUser() = runTest {
        assertThat(bankRow(1000, "Tesco", currency = "GBP").first).isEqualTo(BankTxState.NEEDS_DECISION)
    }
}
