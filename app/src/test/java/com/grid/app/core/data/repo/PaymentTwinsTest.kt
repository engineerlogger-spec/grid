package com.grid.app.core.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.bank.Aspsp
import com.grid.app.core.bank.BankReconciler
import com.grid.app.core.bank.BankSession
import com.grid.app.core.bank.DescriptorCleaner
import com.grid.app.core.bank.RemoteAccount
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.model.BankTxKind
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.CaptureSource
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.TransactionDraft
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.FixedClock
import com.grid.app.feature.capture.CaptureAlerts
import com.grid.app.feature.capture.CaptureOutcome
import com.grid.app.feature.capture.CaptureProcessor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Locale

/** One payment, one entry — whichever way and in whichever order it reaches Grid. */
@RunWith(AndroidJUnit4::class)
class PaymentTwinsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val today = LocalDate.parse("2026-10-05")
    private val clock = FixedClock(today)
    private val noon = today.atTime(12, 0).atZone(clock.zone).toInstant().toEpochMilli()
    private val hour = 3_600_000L
    private lateinit var db: GridDatabase
    private lateinit var transactions: TransactionRepository
    private lateinit var twins: PaymentTwins
    private lateinit var captures: CaptureRepository
    private lateinit var processor: CaptureProcessor
    private lateinit var reconciler: BankReconciler
    private var accountId = 0L

    private object NoAlerts : CaptureAlerts {
        override fun added(capture: CaptureItem, categoryName: String) = Unit
        override fun detected(capture: CaptureItem, suggestions: List<com.grid.app.core.model.Category>) = Unit
    }

    @Before fun setUp() {
        db = GridDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() = db.close()

    private suspend fun TestScope.build() {
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("s.preferences_pb").also { it.delete() } }, Locale.FRANCE)
        transactions = TransactionRepository(db, clock, emptySet())
        twins = PaymentTwins(db, transactions, clock, emptySet())
        captures = CaptureRepository(db, transactions, clock, twins)
        val categories = CategoryRepository(db)
        processor = CaptureProcessor(captures, categories, transactions, settings, NoAlerts, com.grid.app.core.bank.Reversals(db, transactions))
        reconciler = BankReconciler(db, transactions, PendingRepository(db, clock, emptySet()), categories, clock, twins)
        val bank = BankRepository(db, transactions, clock)
        bank.beginAuth(Aspsp("Revolut", "FR", null), "st", null)
        accountId = bank.completeAuth("st", BankSession("s", null, listOf(RemoteAccount("u", "h", "EUR", null, "LT1"))), "EUR").accounts.single().id
    }

    private suspend fun restaurants() = db.categoryDao().byIconKey("restaurant", CategoryKind.EXPENSE)!!.id

    private suspend fun bank(raw: String, amount: Long): Long {
        val row = BankTransactionEntity(
            accountId = accountId, externalId = "x$raw$amount", bookingEpochDay = today.toEpochDay(), occurredAt = noon, amountMinor = amount,
            currency = "EUR", direction = CaptureDirection.OUT, kind = BankTxKind.CARD_SPEND, counterparty = raw,
            counterpartyKey = DescriptorCleaner.clean(raw)?.merchant?.let(MerchantKey::of), state = BankTxState.NEW, rawJson = "{}", createdAt = noon,
        )
        val id = db.bankDao().insertStaged(row)
        reconciler.process(row.copy(id = id), "EUR")
        return db.bankDao().staged(id)!!.transactionId!!
    }

    private suspend fun entries() = transactions.observeAll().first()

    @Test fun theBankSettlesANotificationStillWaitingInDetected() = runTest {
        build()
        // The notification came first and waits for a category…
        assertThat(processor.process(CaptureSource.REVOLUT, "Revolut", "Paid €15.00 at BERFIN", noon - 2 * hour)).isEqualTo(CaptureOutcome.QUEUED)
        // …then the bank books the payment: the notification is settled, nothing left to accept twice.
        val id = bank("BERFIN", 1500)
        assertThat(captures.observeInbox().first()).isEmpty()
        assertThat(entries().map { it.id }).containsExactly(id)
        assertThat(entries().single().occurredAt).isEqualTo(noon - 2 * hour)
    }

    @Test fun acceptingANotificationTheBankBookedMeanwhileJoinsItWithTheUsersCategory() = runTest {
        build()
        processor.process(CaptureSource.REVOLUT, "Revolut", "Paid €15.00 at BERFIN", noon - 2 * hour)
        val waiting = captures.observeInbox().first().single()
        // A bank entry booked without the settling step (as older versions did).
        val bankId = transactions.add(TransactionDraft(TxType.EXPENSE, 1500, "EUR", db.categoryDao().byIconKey("other", CategoryKind.EXPENSE)!!.id, merchant = "Berfin", occurredAt = noon, source = TxSource.BANK, needsReview = true))

        captures.accept(waiting.id, restaurants())

        val only = entries().single()
        assertThat(only.id).isEqualTo(bankId)
        assertThat(only.category.iconKey).isEqualTo("restaurant") // what the user just chose
        assertThat(only.needsReview).isFalse()
    }

    @Test fun twoAppsNotifyingTheSamePaymentMakeOneEntry() = runTest {
        build()
        processor.process(CaptureSource.REVOLUT, "Revolut", "Paid €4.50 at Starbucks", noon)
        captures.accept(captures.observeInbox().first().single().id, restaurants())
        assertThat(processor.process(CaptureSource.GOOGLE_WALLET, "Google Wallet", "Starbucks · €4.50 · Visa ••4421", noon + 60_000)).isEqualTo(CaptureOutcome.JOINED_BANK)
        assertThat(entries()).hasSize(1)
    }

    @Test fun typingInAPaymentAlreadyRecordedAsksFirst() = runTest {
        build()
        bank("STARBUCKS", 450)
        val draft = TransactionDraft(TxType.EXPENSE, 450, "EUR", restaurants(), note = "coffee", occurredAt = noon + hour)
        val result = twins.record(draft, EntryOrigin.MANUAL)
        assertThat(result).isInstanceOf(RecordResult.PossibleDuplicate::class.java)
        assertThat(entries()).hasSize(1)
        assertThat(twins.record(draft, EntryOrigin.MANUAL, force = true)).isInstanceOf(RecordResult.Added::class.java)
        assertThat(entries()).hasSize(2)
    }

    @Test fun aNotificationNamingNoOneJoinsOnlyTheExactAmount() = runTest {
        build()
        val allianz = bank("Allianz Direct Vers.", 1178)
        // €12.60 to an unnamed payee is not the €11.78 Allianz payment, however close.
        assertThat(twins.twinOf(TxType.EXPENSE, 1260, "EUR", noon + 5 * hour, null, EntryOrigin.NOTIFICATION)).isNull()
        assertThat(twins.twinOf(TxType.EXPENSE, 1178, "EUR", noon + 5 * hour, null, EntryOrigin.NOTIFICATION)?.id).isEqualTo(allianz)
    }

    @Test fun aJoinEarlierVersionsMadeOnACloseAmountAloneIsUndone() = runTest {
        build()
        val allianz = bank("Allianz Direct Vers.", 1178)
        val paypal = db.paymentMethodDao().all().first { it.kind == com.grid.app.core.model.PaymentKind.PAYPAL }.id
        val captureId = db.captureDao().insert(
            com.grid.app.core.data.db.entities.CaptureEntity(
                source = CaptureSource.PAYPAL, postedAt = noon + 5 * hour, title = "PayPal", text = "€12.60", amountMinor = 1260, currency = "EUR",
                merchant = null, status = com.grid.app.core.model.CaptureStatus.ADDED, transactionId = allianz, dedupeKey = "pp",
            ),
        )
        db.transactionDao().update(db.transactionDao().get(allianz)!!.copy(captureId = captureId, occurredAt = noon + 5 * hour, paymentMethodId = paypal))

        twins.mergeExisting()
        val back = transactions.get(allianz)!!
        assertThat(back.occurredAt).isEqualTo(noon)
        assertThat(back.captureId).isNull()
        assertThat(back.method?.kind).isEqualTo(com.grid.app.core.model.PaymentKind.REVOLUT)
        assertThat(db.captureDao().get(captureId)!!.status).isEqualTo(com.grid.app.core.model.CaptureStatus.DISMISSED)
        assertThat(entries()).hasSize(1)
    }

    @Test fun doublesMadeBeforeAreMergedWhenTheAppOpens() = runTest {
        build()
        val bankId = bank("BERFIN", 1500)
        // A notification entry for the same payment, as older versions could leave.
        db.transactionDao().insert(
            com.grid.app.core.data.db.entities.TransactionEntity(
                type = TxType.EXPENSE, amountMinor = 1500, currency = "EUR", categoryId = restaurants(), merchant = "BERFIN",
                occurredAt = noon - hour, createdAt = 0, updatedAt = 0, source = TxSource.CAPTURE, captureId = 77,
            ),
        )
        assertThat(entries()).hasSize(2)
        assertThat(twins.mergeExisting()).isEqualTo(1)
        assertThat(entries().map { it.id }).containsExactly(bankId)
    }
}
