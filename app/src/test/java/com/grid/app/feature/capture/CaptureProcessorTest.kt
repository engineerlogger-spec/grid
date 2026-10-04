package com.grid.app.feature.capture

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.CaptureItem
import com.grid.app.core.data.repo.CaptureRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.CaptureSource.PAYPAL
import com.grid.app.core.model.CaptureSource.REVOLUT
import com.grid.app.core.model.CaptureStatus
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.PaymentKind
import com.grid.app.core.model.TxSource
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
class CaptureProcessorTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: GridDatabase
    private val clock = FixedClock(LocalDate.parse("2026-10-04"))
    private val now = clock.millis()

    private class FakeAlerts : CaptureAlerts {
        val added = mutableListOf<Pair<CaptureItem, String>>()
        val detected = mutableListOf<Pair<CaptureItem, List<Category>>>()
        override fun added(capture: CaptureItem, categoryName: String) { added += capture to categoryName }
        override fun detected(capture: CaptureItem, suggestions: List<Category>) { detected += capture to suggestions }
    }

    private val alerts = FakeAlerts()
    private lateinit var settings: SettingsRepository
    private lateinit var captures: CaptureRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var processor: CaptureProcessor

    @Before fun setUp() {
        db = GridDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() = db.close()

    private fun TestScope.build() {
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("s.preferences_pb").also { it.delete() } }, Locale.FRANCE)
        transactions = TransactionRepository(db, clock, emptySet())
        captures = CaptureRepository(db, transactions, clock)
        processor = CaptureProcessor(captures, CategoryRepository(db), transactions, settings, alerts)
    }

    private suspend fun restaurants() = db.categoryDao().byIconKey("restaurant", CategoryKind.EXPENSE)!!.id

    @Test fun firstPaymentIsQueuedThenLearnedMerchantIsAutoAdded() = runTest {
        build()
        assertThat(processor.process(REVOLUT, "Revolut", "Paid €4.50 at Starbucks", now)).isEqualTo(CaptureOutcome.QUEUED)
        val queued = alerts.detected.single().first
        assertThat(alerts.detected.single().second).hasSize(2)
        assertThat(captures.observeInbox().first().map { it.id }).containsExactly(queued.id)

        captures.accept(queued.id, restaurants())
        val first = transactions.observeAll().first().single()
        assertThat(first.merchant).isEqualTo("Starbucks")
        assertThat(first.source).isEqualTo(TxSource.CAPTURE)
        assertThat(first.method?.kind).isEqualTo(PaymentKind.REVOLUT)
        assertThat(captures.observeInbox().first()).isEmpty()

        // Same merchant an hour later: categorised automatically.
        assertThat(processor.process(REVOLUT, "Revolut", "Paid €4.80 at STARBUCKS", now + 3_600_000)).isEqualTo(CaptureOutcome.AUTO_ADDED)
        assertThat(alerts.added.single().second).isEqualTo("Restaurants")
        assertThat(transactions.observeAll().first()).hasSize(2)
    }

    @Test fun repostedNotificationIsDeduplicated() = runTest {
        build()
        processor.process(REVOLUT, "Revolut", "Paid €4.50 at Starbucks", now)
        assertThat(processor.process(REVOLUT, "Revolut", "Paid €4.50 at Starbucks", now + 60_000)).isEqualTo(CaptureOutcome.DUPLICATE)
    }

    @Test fun foreignCurrencyIsNeverAutoAdded() = runTest {
        build()
        processor.process(REVOLUT, "Revolut", "Paid €4.50 at Tesco", now)
        captures.accept(alerts.detected.single().first.id, restaurants())
        assertThat(processor.process(REVOLUT, "Revolut", "Paid £3.40 at Tesco", now + 3_600_000)).isEqualTo(CaptureOutcome.QUEUED)
    }

    @Test fun incomingMoneyIsQueuedWithIncomeSuggestions() = runTest {
        build()
        processor.process(PAYPAL, "Payment received", "You received €15.00 EUR from Sam.", now)
        assertThat(alerts.detected.single().second.all { it.kind == CategoryKind.INCOME }).isTrue()
    }

    @Test fun disabledSourceIsSkipped() = runTest {
        build()
        settings.setCaptureSource(REVOLUT, false)
        assertThat(processor.process(REVOLUT, "Revolut", "Paid €4.50 at Starbucks", now)).isEqualTo(CaptureOutcome.DISABLED)
    }

    @Test fun unparsedKeptForDiagnostics() = runTest {
        build()
        assertThat(processor.process(REVOLUT, "Revolut", "Payment at Uber", now)).isEqualTo(CaptureOutcome.UNPARSED)
        assertThat(captures.observeUnparsed().first().single().text).isEqualTo("Payment at Uber")
    }

    @Test fun undoAutoAddPutsItBackInTheInbox() = runTest {
        build()
        processor.process(REVOLUT, "Revolut", "Paid €4.50 at Starbucks", now)
        captures.accept(alerts.detected.single().first.id, restaurants())
        processor.process(REVOLUT, "Revolut", "Paid €6.00 at Starbucks", now + 3_600_000)
        val auto = alerts.added.single().first
        captures.undo(auto.id)
        assertThat(transactions.observeAll().first()).hasSize(1)
        assertThat(captures.get(auto.id)!!.status).isEqualTo(CaptureStatus.NEW)
    }
}
