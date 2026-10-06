package com.grid.app.core.bank

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.PendingRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.BankStatus
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.PaymentKind
import com.grid.app.core.model.TxType
import com.grid.app.core.time.FixedClock
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

@RunWith(AndroidJUnit4::class)
class BankSyncTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: GridDatabase
    private val today = LocalDate.parse("2026-10-04")
    private val clock = FixedClock(today)
    private lateinit var transactions: TransactionRepository
    private lateinit var bank: BankRepository
    private lateinit var sync: BankSync
    private lateinit var reversals: Reversals

    /** Wraps the demo bank to record requests or fail on demand. */
    private inner class Recording(var failWith: BankError? = null) : BankConnector by DemoBankConnector(clock) {
        val froms = mutableListOf<LocalDate?>()
        val longest = mutableListOf<Boolean>()
        override suspend fun transactions(accountUid: String, dateFrom: LocalDate?, continuationKey: String?, longest: Boolean): TxPage {
            failWith?.let { throw it }
            froms += dateFrom
            this.longest += longest
            return DemoBankConnector(clock).transactions(accountUid, dateFrom, continuationKey, longest)
        }
    }

    private val connector = Recording()

    @Before fun setUp() {
        db = GridDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() = db.close()

    private lateinit var settings: SettingsRepository
    private fun settingsFor() = settings

    private suspend fun TestScope.connect(using: BankConnector = connector) {
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("s.preferences_pb").also { it.delete() } }, Locale.FRANCE)
        transactions = TransactionRepository(db, clock, emptySet())
        bank = BankRepository(db, transactions, clock)
        val reconciler = BankReconciler(db, transactions, PendingRepository(db, clock, emptySet()), CategoryRepository(db), clock)
        reversals = Reversals(db, transactions)
        sync = BankSync(db, bank, { using }, reconciler, settings, clock, transactions, reversals)

        val demo = DemoBankConnector(clock)
        bank.beginAuth(demo.aspsps("FR").single(), "st", today.minusMonths(3).toEpochDay())
        bank.completeAuth("st", demo.createSession("demo"), "EUR")
    }

    @Test fun firstSyncImportsHistoryAndSortsItOut() = runTest {
        connect()
        val result = sync.run() as SyncResult.Ok
        assertThat(result.fetched).isGreaterThan(10)
        assertThat(result.toReview).isGreaterThan(0)

        val ledger = transactions.observeAll().first()
        val byMerchant = ledger.groupBy { it.merchant }
        assertThat(byMerchant["Lidl"]!!.all { it.category.iconKey == "groceries" && !it.needsReview }).isTrue()
        assertThat(byMerchant["Lidl"]).hasSize(3)
        // Everything is booked: a transfer to a person under Other, money from a company as income.
        assertThat(byMerchant["J. Dupont"]!!.all { it.type == TxType.EXPENSE }).isTrue()
        assertThat(byMerchant["Acme SAS"]!!.all { it.type == TxType.INCOME }).isTrue()
        assertThat(byMerchant["Netflix"]!!.all { it.method?.kind == PaymentKind.PAYPAL }).isTrue()
        assertThat(byMerchant["EDF"]).isNotEmpty() // direct debits are booked straight away
        assertThat(byMerchant["Starbucks"]!!.single().category.iconKey).isEqualTo("restaurant") // MCC 5814
        assertThat(byMerchant.keys).containsNoneOf("To EUR Vault", "Top-Up by *4421")
        // Money sent to the holder's own account is spending; money from it is a move (Savings), not income.
        assertThat(byMerchant["Sam Taylor"]!!.map { it.type }.toSet()).containsExactly(TxType.EXPENSE)
        assertThat(byMerchant["Uber"]).hasSize(1) // still pending at the bank: shown straight away

        assertThat(db.bankDao().stagedByState(BankTxState.NEEDS_DECISION)).isEmpty()
        assertThat(db.bankDao().stagedByState(BankTxState.IGNORED).single().counterparty).isEqualTo("To EUR Vault")
        // The demo account is held by Sam Taylor: transfers with that name and the card top-up are moves.
        assertThat(db.bankDao().stagedByState(BankTxState.OWN_TRANSFER).map { BankRepository.displayName(it) }.toSet())
            .containsExactly("Sam Taylor", "Top-Up by *4421")
        assertThat(db.bankDao().stagedByState(BankTxState.NEW)).isEmpty()
        assertThat(bank.connection()!!.lastSyncAt).isEqualTo(clock.millis())
        // Only the EUR account is synced (the USD one is off by default), and the first fetch asks for the whole history.
        assertThat(connector.froms).containsExactly(null)
        assertThat(connector.longest).containsExactly(true)
    }

    @Test fun secondSyncAddsNothingAndRereadsAFewDays() = runTest {
        connect()
        sync.run()
        val before = transactions.observeAll().first().size
        val again = sync.run() as SyncResult.Ok
        assertThat(again.booked).isEqualTo(0)
        assertThat(again.toReview).isEqualTo(0)
        assertThat(transactions.observeAll().first()).hasSize(before)
        assertThat(connector.froms.last()).isEqualTo(today.minusDays(5))
    }

    @Test fun expiredSessionAsksToReconnect() = runTest {
        connect()
        connector.failWith = BankError.SessionExpired()
        assertThat(sync.run()).isEqualTo(SyncResult.Expired)
        assertThat(bank.connection()!!.status).isEqualTo(BankStatus.EXPIRED)
    }

    @Test fun consentPastItsEndIsExpiredWithoutCallingTheBank() = runTest {
        connect()
        db.bankDao().updateConnection(bank.connection()!!.copy(validUntil = clock.millis() - 1))
        assertThat(sync.run()).isEqualTo(SyncResult.Expired)
        assertThat(connector.froms).isEmpty()
    }

    @Test fun networkTroubleKeepsTheConnection() = runTest {
        connect()
        connector.failWith = BankError.Network(java.io.IOException("offline"))
        assertThat(sync.run()).isInstanceOf(SyncResult.Failed::class.java)
        assertThat(bank.connection()!!.status).isEqualTo(BankStatus.ACTIVE)
        assertThat(bank.connection()!!.lastError).isEqualTo("offline")
    }

    @Test fun unexpectedTroubleFailsTheSyncInsteadOfCrashing() = runTest {
        connect()
        val broken = BankSync(db, bank, { throwingConnector }, BankReconciler(db, transactions, PendingRepository(db, clock, emptySet()), CategoryRepository(db), clock), settingsFor(), clock, transactions, reversals)
        assertThat(broken.run()).isInstanceOf(SyncResult.Failed::class.java)
    }

    private val throwingConnector = object : BankConnector by DemoBankConnector(clock) {
        override suspend fun transactions(accountUid: String, dateFrom: LocalDate?, continuationKey: String?, longest: Boolean): TxPage = error("unexpected shape")
    }

    /** Returns exactly [txs], whatever is asked. */
    private inner class Scripted : BankConnector by DemoBankConnector(clock) {
        var txs: List<RemoteTx> = emptyList()
        override suspend fun transactions(accountUid: String, dateFrom: LocalDate?, continuationKey: String?, longest: Boolean) = TxPage(txs, null)
    }

    private fun uber(status: String, ref: String? = null) = RemoteTx(
        entryReference = ref, amount = "12.00", currency = "EUR", creditDebit = "DBIT", status = status,
        transactionDate = today.minusDays(1).toString(), bookingDate = if (status == "BOOK") today.toString() else null,
        creditorName = "Uber", bankTxCode = "CARD_PAYMENT",
    )

    @Test fun pendingPaymentIsReplacedByItsBookedVersionKeepingTheUsersChoices() = runTest {
        val scripted = Scripted()
        connect(scripted)
        scripted.txs = listOf(uber("PDNG"))
        sync.run()
        val entry = transactions.observeAll().first().single { it.merchant == "Uber" }
        val restaurant = db.categoryDao().byIconKey("restaurant", com.grid.app.core.model.CategoryKind.EXPENSE)!!.id
        transactions.recategorize(entry.id, restaurant) // the user re-sorts it while still pending

        scripted.txs = listOf(uber("BOOK", ref = "e-9"))
        sync.run()
        val ubers = transactions.observeAll().first().filter { it.merchant == "Uber" }
        assertThat(ubers.map { it.id }).containsExactly(entry.id)
        assertThat(ubers.single().category.id).isEqualTo(restaurant)
        assertThat(db.bankDao().stagedWithPrefix(1, BankSync.PENDING_PREFIX)).isEmpty()
        assertThat(db.bankDao().stagedByState(BankTxState.BOOKED).single { it.counterparty == "Uber" }.transactionId).isEqualTo(entry.id)
    }

    @Test fun everythingTheBankSendsIsStoredEvenWhatIsNotShown() = runTest {
        val scripted = Scripted()
        connect(scripted)
        scripted.txs = listOf(
            uber("SCHD"),
            uber("BOOK", ref = "zero").copy(amount = "0.00"),
            uber("BOOK", ref = "undated").copy(transactionDate = null, bookingDate = null),
        )
        sync.run()
        val ignored = db.bankDao().stagedByState(BankTxState.IGNORED).map { it.externalId }
        assertThat(ignored).containsExactly("zero", "SCHD:${ExternalIds.assign(listOf(uber("SCHD"))).single().first}")
        // No date from the bank: kept and shown on the day it was fetched.
        assertThat(transactions.observeAll().first().filter { it.merchant == "Uber" }).hasSize(1)
    }

    @Test fun aDateTheUserChangedStaysAfterSyncs() = runTest {
        connect()
        sync.run()
        val sent = transactions.observeAll().first().first { it.merchant == "Sam Taylor" && it.type == TxType.EXPENSE }
        val before = transactions.observeAll().first().size
        val newDate = today.atTime(12, 0).atZone(clock.zone).toInstant().toEpochMilli()
        transactions.update(sent.id, com.grid.app.core.model.TransactionDraft(
            sent.type, sent.amountMinor, sent.currency, sent.category.id, sent.method?.id, sent.merchant, sent.note, newDate, sent.source,
        ))
        sync.refreshLocal()
        sync.run()
        sync.refreshLocal()
        assertThat(transactions.get(sent.id)!!.occurredAt).isEqualTo(newDate)
        assertThat(transactions.observeAll().first()).hasSize(before)
    }

    private suspend fun ledgerUbers() = transactions.observeAll().first().filter { it.merchant == "Uber" }
    private suspend fun revertedUbers() = transactions.observeReverted(null).first().filter { it.merchant == "Uber" }

    @Test fun pendingPaymentTheBankDropsShowsAsReverted() = runTest {
        val scripted = Scripted()
        connect(scripted)
        scripted.txs = listOf(uber("PDNG"))
        sync.run()
        val entry = ledgerUbers().single()
        scripted.txs = emptyList()
        sync.run()
        sync.run()
        assertThat(ledgerUbers()).isEmpty()
        assertThat(revertedUbers().map { it.id to it.reverted }).containsExactly(entry.id to true)
        assertThat(db.bankDao().stagedByState(BankTxState.REVERTED).single().counterparty).isEqualTo("Uber")
    }

    @Test fun aPaymentRecordedFromItsNotificationIsRevertedToo() = runTest {
        val scripted = Scripted()
        connect(scripted)
        val other = db.categoryDao().byIconKey(com.grid.app.core.data.db.Seed.ICON_OTHER, com.grid.app.core.model.CategoryKind.EXPENSE)!!.id
        val noted = transactions.add(
            com.grid.app.core.model.TransactionDraft(
                TxType.EXPENSE, 1200, "EUR", other, merchant = "Uber",
                occurredAt = clock.millis() - 20 * 3_600_000L, source = com.grid.app.core.model.TxSource.CAPTURE,
            ),
        )
        scripted.txs = listOf(uber("PDNG"))
        sync.run()
        assertThat(db.bankDao().stagedLinkedTo(noted)).isNotNull()
        scripted.txs = emptyList()
        sync.run()
        assertThat(ledgerUbers()).isEmpty()
        assertThat(revertedUbers().single().id).isEqualTo(noted)
    }

    @Test fun cancelledStatusRevertsABookedPayment() = runTest {
        val scripted = Scripted()
        connect(scripted)
        scripted.txs = listOf(uber("BOOK", ref = "e-9"))
        sync.run()
        scripted.txs = listOf(uber("CNCL", ref = "e-9"))
        sync.run()
        assertThat(ledgerUbers()).isEmpty()
        assertThat(revertedUbers()).hasSize(1)
    }

    /**
     * The owner's evening of 6 October, as Revolut's feed and app showed it: two Bolt rides paid through PayPal. Each
     * first charged a price (€12.60, €15.50) that Revolut reverted when it charged the final one (€19.70, €23.20).
     * The feed lists only the final ones, both pending under the same descriptor (it names the day, not the ride).
     */
    private fun bolt(ref: String, amount: String, status: String = "PDNG") = RemoteTx(
        entryReference = ref, amount = amount, currency = "EUR", creditDebit = "DBIT", status = status,
        bookingDate = today.toString(), creditorName = "Paypal *bolt.eu/o/2610061", remittance = listOf("Paypal *bolt.eu/o/2610061"),
        bankTxCode = "CARD_PAYMENT",
    )
    private val ride1 = "6ac529a0-5dcf-a4ab-9a25-9d4a03329917"
    private val ride2 = "6ac537f8-224a-a751-b4f9-86aaa48a9996"

    /** An entry recorded from [source]'s notification of a payment out, [minutesAgo] before now. */
    private suspend fun notifiedBy(source: com.grid.app.core.model.CaptureSource, amount: Long, merchant: String, minutesAgo: Long): Long {
        val at = clock.millis() - minutesAgo * 60_000L
        val other = db.categoryDao().byIconKey(com.grid.app.core.data.db.Seed.ICON_OTHER, com.grid.app.core.model.CategoryKind.EXPENSE)!!.id
        val captureId = db.captureDao().insert(
            com.grid.app.core.data.db.entities.CaptureEntity(
                source = source, postedAt = at, title = source.name, text = "Paid €$amount at $merchant", amountMinor = amount, currency = "EUR",
                merchant = merchant, status = com.grid.app.core.model.CaptureStatus.ADDED, dedupeKey = "$source$amount$minutesAgo",
            ),
        )
        return transactions.add(
            com.grid.app.core.model.TransactionDraft(
                TxType.EXPENSE, amount, "EUR", other, merchant = merchant, occurredAt = at,
                source = com.grid.app.core.model.TxSource.CAPTURE, captureId = captureId,
            ),
        )
    }

    private suspend fun revertedAmounts() = transactions.observeReverted(null).first().map { it.amountMinor }
    private suspend fun countedAmounts() = transactions.observeAll().first().map { it.amountMinor }

    @Test fun twoRidesUnderTheSameDescriptorAreTwoPayments() = runTest {
        val scripted = Scripted()
        connect(scripted)
        scripted.txs = listOf(bolt(ride2, "23.20"), bolt(ride1, "19.70"))
        sync.run()
        sync.refreshLocal()
        assertThat(countedAmounts()).containsAtLeast(1970L, 2320L)
        assertThat(revertedAmounts()).isEmpty()
    }

    @Test fun theFirstPricesRevolutRevertedShowAsRevertedAndTheFinalOnesCount() = runTest {
        val scripted = Scripted()
        connect(scripted)
        db.bankDao().updateConnection(bank.connection()!!.copy(aspspName = "Revolut", createdAt = clock.millis() - 10 * 86_400_000L))
        val paypal = com.grid.app.core.model.CaptureSource.PAYPAL
        notifiedBy(paypal, 1260, "Bolt", minutesAgo = 200) // ride 1, first price: reverted
        notifiedBy(paypal, 1970, "Bolt", minutesAgo = 80) // ride 1, final price
        notifiedBy(paypal, 1550, "Bolt", minutesAgo = 79) // ride 2, first price: reverted
        notifiedBy(paypal, 2320, "Bolt", minutesAgo = 20) // ride 2, final price
        // The day before: Allianz, close to €12.60 but another payment.
        val allianz = uber("BOOK", ref = "allianz").copy(amount = "11.78", creditorName = "Allianz Direct Vers.", bankTxCode = "TRANSFER")
        scripted.txs = listOf(bolt(ride1, "19.70"), bolt(ride2, "23.20"), allianz)
        sync.run()
        assertThat(revertedAmounts()).containsExactly(1260L, 1550L)
        assertThat(countedAmounts()).containsExactly(1970L, 2320L, 1178L)
        // Nothing changes when the app opens again.
        sync.refreshLocal()
        assertThat(revertedAmounts()).containsExactly(1260L, 1550L)
    }

    @Test fun aNotifiedPaymentTheBankListsAfterAllComesBack() = runTest {
        val scripted = Scripted()
        connect(scripted)
        db.bankDao().updateConnection(bank.connection()!!.copy(aspspName = "Revolut", createdAt = clock.millis() - 10 * 86_400_000L))
        val late = notifiedBy(com.grid.app.core.model.CaptureSource.REVOLUT, 1200, "Uber", minutesAgo = 90)
        sync.run()
        assertThat(revertedAmounts()).containsExactly(1200L)
        scripted.txs = listOf(uber("PDNG"))
        sync.run()
        assertThat(revertedAmounts()).isEmpty()
        assertThat(ledgerUbers().map { it.id }).containsExactly(late)
        assertThat(db.bankDao().stagedLinkedTo(late)).isNotNull()
    }

    @Test fun aPendingPaymentTakenForRevertedThatTheBankStillListsComesBack() = runTest {
        val scripted = Scripted()
        connect(scripted)
        scripted.txs = listOf(bolt(ride1, "19.70"), bolt(ride2, "23.20"))
        sync.run()
        // What 3.4.1 did: the first ride taken for replaced by the second.
        reversals.revertRow(db.bankDao().stagedWithPrefix(1, BankSync.PENDING_PREFIX).single { it.amountMinor == 1970L })
        assertThat(revertedAmounts()).containsExactly(1970L)
        sync.refreshLocal() // once, on the first open of the fixed version
        assertThat(revertedAmounts()).isEmpty()
        reversals.revertRow(db.bankDao().stagedWithPrefix(1, BankSync.PENDING_PREFIX).single { it.amountMinor == 1970L })
        sync.run() // and whenever the bank still lists it as pending
        assertThat(revertedAmounts()).isEmpty()
        assertThat(countedAmounts()).containsAtLeast(1970L, 2320L)
    }

    @Test fun aFinalAmountWithATipSettlesThePendingPayment() = runTest {
        val scripted = Scripted()
        connect(scripted)
        scripted.txs = listOf(uber("PDNG"))
        sync.run()
        scripted.txs = listOf(uber("BOOK", ref = "e-10").copy(amount = "13.20"))
        sync.run()
        assertThat(ledgerUbers().map { it.amountMinor }).containsExactly(1320L)
        assertThat(revertedUbers()).isEmpty()
    }

    @Test fun countItAnywayKeepsItCountedAfterLaterSyncs() = runTest {
        val scripted = Scripted()
        connect(scripted)
        scripted.txs = listOf(uber("PDNG"))
        sync.run()
        scripted.txs = emptyList()
        sync.run()
        reversals.countAnyway(revertedUbers().single().id)
        sync.run()
        assertThat(ledgerUbers()).hasSize(1)
        assertThat(revertedUbers()).isEmpty()
    }

    @Test fun aRevolutNotificationTheBankNeverListedIsRevertedAfterAWhile() = runTest {
        val scripted = Scripted()
        connect(scripted)
        db.bankDao().updateConnection(bank.connection()!!.copy(aspspName = "Revolut", createdAt = clock.millis() - 10 * 86_400_000L))
        val other = db.categoryDao().byIconKey(com.grid.app.core.data.db.Seed.ICON_OTHER, com.grid.app.core.model.CategoryKind.EXPENSE)!!.id
        suspend fun notified(amount: Long, hoursAgo: Long): Long {
            val at = clock.millis() - hoursAgo * 3_600_000L
            val captureId = db.captureDao().insert(
                com.grid.app.core.data.db.entities.CaptureEntity(
                    source = com.grid.app.core.model.CaptureSource.REVOLUT, postedAt = at, title = "Revolut", text = "Paid at Shop",
                    amountMinor = amount, currency = "EUR", merchant = "Shop", status = com.grid.app.core.model.CaptureStatus.ADDED, dedupeKey = "k$amount",
                ),
            )
            return transactions.add(
                com.grid.app.core.model.TransactionDraft(
                    TxType.EXPENSE, amount, "EUR", other, merchant = "Shop", occurredAt = at,
                    source = com.grid.app.core.model.TxSource.CAPTURE, captureId = captureId,
                ),
            )
        }
        val gone = notified(4_500, hoursAgo = 5)
        val fresh = notified(700, hoursAgo = 0)
        val listed = notified(1_200, hoursAgo = 20) // the bank has a €12.00 payment that day (Uber)
        scripted.txs = listOf(uber("BOOK", ref = "e-11").copy(creditorName = "Uber BV"))
        sync.run()
        val ledger = transactions.observeAll().first().map { it.id }
        assertThat(ledger).containsAtLeast(fresh, listed)
        assertThat(ledger).doesNotContain(gone)
        assertThat(transactions.observeReverted(null).first().map { it.id }).containsExactly(gone)
    }

    @Test fun notConnectedWithoutSession() = runTest {
        connect()
        bank.disconnect()
        assertThat(sync.run()).isEqualTo(SyncResult.NotConnected)
    }
}
