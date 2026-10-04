package com.grid.app.core.bank

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.PendingRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.BankStatus
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.PaymentKind
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

    /** Wraps the demo bank to record requests or fail on demand. */
    private inner class Recording(var failWith: BankError? = null) : BankConnector by DemoBankConnector(clock) {
        val froms = mutableListOf<LocalDate>()
        override suspend fun transactions(accountUid: String, dateFrom: LocalDate, continuationKey: String?): TxPage {
            failWith?.let { throw it }
            froms += dateFrom
            return DemoBankConnector(clock).transactions(accountUid, dateFrom, continuationKey)
        }
    }

    private val connector = Recording()

    @Before fun setUp() {
        db = GridDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() = db.close()

    private lateinit var settings: SettingsRepository
    private fun settingsFor() = settings

    private suspend fun TestScope.connect() {
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("s.preferences_pb").also { it.delete() } }, Locale.FRANCE)
        transactions = TransactionRepository(db, clock, emptySet())
        bank = BankRepository(db, transactions, clock)
        val reconciler = BankReconciler(db, transactions, PendingRepository(db, clock, emptySet()), clock)
        sync = BankSync(db, bank, { connector }, reconciler, settings, clock)

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
        assertThat(byMerchant["Lidl"]!!.all { it.needsReview }).isTrue()
        assertThat(byMerchant["Lidl"]).hasSize(3)
        assertThat(byMerchant["Netflix"]!!.all { it.method?.kind == PaymentKind.PAYPAL }).isTrue()
        assertThat(byMerchant["EDF"]).isNotEmpty() // direct debits are booked straight away
        assertThat(byMerchant["Starbucks"]!!.single().category.iconKey).isEqualTo("restaurant") // MCC 5814
        assertThat(byMerchant.keys).containsNoneOf("Uber", "To EUR Vault", "J. Dupont", "Acme SAS")

        val decide = db.bankDao().stagedByState(BankTxState.NEEDS_DECISION).map { BankRepository.displayName(it) }.toSet()
        assertThat(decide).containsExactly("J. Dupont", "Acme SAS")
        assertThat(db.bankDao().stagedByState(BankTxState.IGNORED).single().counterparty).isEqualTo("To EUR Vault")
        assertThat(db.bankDao().stagedByState(BankTxState.NEW)).isEmpty()
        assertThat(bank.connection()!!.lastSyncAt).isEqualTo(clock.millis())
        // Only the EUR account is synced; the USD one is off by default.
        assertThat(connector.froms).containsExactly(today.minusMonths(3))
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
        val broken = BankSync(db, bank, { throwingConnector }, BankReconciler(db, transactions, PendingRepository(db, clock, emptySet()), clock), settingsFor(), clock)
        assertThat(broken.run()).isInstanceOf(SyncResult.Failed::class.java)
    }

    private val throwingConnector = object : BankConnector by DemoBankConnector(clock) {
        override suspend fun transactions(accountUid: String, dateFrom: LocalDate, continuationKey: String?): TxPage = error("unexpected shape")
    }

    @Test fun notConnectedWithoutSession() = runTest {
        connect()
        bank.disconnect()
        assertThat(sync.run()).isEqualTo(SyncResult.NotConnected)
    }
}
