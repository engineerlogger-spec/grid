package com.grid.app.core.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.bank.Aspsp
import com.grid.app.core.bank.BankReconciler
import com.grid.app.core.bank.BankSession
import com.grid.app.core.bank.DescriptorCleaner
import com.grid.app.core.bank.RemoteAccount
import com.grid.app.core.bank.SecretBox
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.PendingRepository
import com.grid.app.core.data.repo.SubscriptionRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.BankTxKind
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.time.FixedClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AiAssistantTest {

    @get:Rule val tmp = TemporaryFolder()

    private val today = LocalDate.parse("2026-10-05")
    private val clock = FixedClock(today)
    private lateinit var db: GridDatabase
    private lateinit var server: MockWebServer
    private lateinit var transactions: TransactionRepository
    private lateinit var subscriptions: SubscriptionRepository
    private lateinit var reconciler: BankReconciler
    private lateinit var settings: SettingsRepository
    private lateinit var assistant: AiAssistant
    private var accountId = 0L
    private var ext = 0

    private object Plain : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    @Before fun setUp() {
        db = GridDatabase.inMemory(ApplicationProvider.getApplicationContext())
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        db.close()
        server.close()
    }

    private suspend fun TestScope.ready() {
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("s.preferences_pb").also { it.delete() } }, Locale.FRANCE)
        transactions = TransactionRepository(db, clock, emptySet())
        subscriptions = SubscriptionRepository(db, clock, emptySet())
        val categories = CategoryRepository(db)
        reconciler = BankReconciler(db, transactions, PendingRepository(db, clock, emptySet()), categories, clock)
        val bank = BankRepository(db, transactions, clock)
        bank.beginAuth(Aspsp("Revolut", "FR", null), "st", null)
        // Revolut names the account after its holder.
        accountId = bank.completeAuth("st", BankSession("s", null, listOf(RemoteAccount("u", "h", "EUR", "Sam Taylor", "LT1"))), "EUR").accounts.single().id
        val keys = AiKeyStore(tmp.newFile("ai.key"), Plain).apply { save("test-key") }
        val gemini = GeminiClient(OkHttpClient(), keys::load, listOf("gemini-test"), server.url("/v1beta").toString())
        assistant = AiAssistant(db, gemini, keys, transactions, subscriptions, categories, settings, clock)
    }

    private fun key(raw: String) = DescriptorCleaner.clean(raw)!!.merchant.let(MerchantKey::of)!!

    private suspend fun pay(raw: String, amount: Long, date: String, kind: BankTxKind = BankTxKind.CARD_SPEND): BankTransactionEntity {
        val at = LocalDate.parse(date).atTime(12, 0).atZone(clock.zone).toInstant().toEpochMilli()
        val row = BankTransactionEntity(
            accountId = accountId, externalId = "x${ext++}", bookingEpochDay = LocalDate.parse(date).toEpochDay(), occurredAt = at,
            amountMinor = amount, currency = "EUR", direction = CaptureDirection.OUT, kind = kind, counterparty = raw,
            counterpartyKey = key(raw), state = BankTxState.NEW, rawJson = "{}", createdAt = at,
        )
        val id = db.bankDao().insertStaged(row)
        reconciler.process(row.copy(id = id), "EUR")
        return db.bankDao().staged(id)!!
    }

    private fun gemini(text: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .body("""{"candidates":[{"content":{"parts":[{"text":${kotlinx.serialization.json.JsonPrimitive(text)}}]}}]}""").build()

    @Test fun recognisesPayeesAndFindsBillsWithoutTouchingTheUsersChoicesOrSendingTheirName() = runTest {
        ready()
        val market = listOf(pay("MARKET LE B MESN", 877, "2026-09-02"), pay("MARKET LE B MESN", 1240, "2026-09-20"))
        val berfin = pay("BERFIN", 1500, "2026-09-12")
        val lidl = pay("LIDL 1234", 2340, "2026-09-15")
        val sfr = listOf("2026-07-05" to 1499L, "2026-08-05" to 1499L, "2026-09-05" to 2240L, "2026-10-03" to 1499L)
            .map { (d, a) -> pay("SFR", a, d, BankTxKind.TRANSFER_OUT) }
        pay("Sam Taylor", 5_000, "2026-09-25", BankTxKind.TRANSFER_OUT) // the holder's own account: never sent
        // The user moved Lidl to Health themselves.
        val lidlTx = transactions.get(lidl.transactionId!!)!!
        transactions.update(lidlTx.id, lidlTx.toDraft().copy(categoryId = db.categoryDao().byIconKey("health", CategoryKind.EXPENSE)!!.id))

        server.enqueue(
            gemini(
                """[{"key":"${key("MARKET LE B MESN")}","name":"Carrefour Market","about":"Supermarket","category":"groceries","confidence":0.9},
                    {"key":"${key("BERFIN")}","name":"Berfin Kebab","about":"Kebab shop","category":"restaurant","confidence":0.6},
                    {"key":"${key("LIDL 1234")}","name":"Lidl","about":"Supermarket","category":"groceries","confidence":0.95},
                    {"key":"sfr","name":"SFR","about":"Mobile operator","category":"bills","confidence":0.95}]""",
            ),
        )
        server.enqueue(gemini("""[{"key":"sfr","name":"SFR","cadence":"monthly","amount":16.00,"varies":true,"active":true,"confidence":0.95}]"""))

        val result = assistant.run() as AiRunResult.Ok
        assertThat(result.bills).isEqualTo(1)

        val recognition = server.takeRequest()
        assertThat(recognition.url.encodedPath).isEqualTo("/v1beta/models/gemini-test:generateContent")
        assertThat(recognition.headers["x-goog-api-key"]).isEqualTo("test-key")
        val sent = recognition.body!!.utf8()
        assertThat(sent).contains("Market Le B Mesn")
        assertThat(sent).doesNotContain("Sam Taylor")

        // A sure name replaces the terminal's; an unsure one doesn't, but its category is still used.
        market.forEach { assertThat(transactions.get(it.transactionId!!)!!.merchant).isEqualTo("Carrefour Market") }
        assertThat(transactions.get(market[0].transactionId!!)!!.category.iconKey).isEqualTo("groceries")
        assertThat(transactions.get(berfin.transactionId!!)!!.merchant).isEqualTo("Berfin")
        assertThat(transactions.get(berfin.transactionId!!)!!.category.iconKey).isEqualTo("restaurant")
        // What the user chose stays.
        assertThat(transactions.get(lidl.transactionId!!)!!.category.iconKey).isEqualTo("health")

        // SFR: a detected bill whose amount varies, linked to every payment, next charge after today.
        val bill = subscriptions.all().single()
        assertThat(bill.detected).isTrue()
        assertThat(bill.amountVaries).isTrue()
        assertThat(bill.payeeKey).isEqualTo("sfr")
        assertThat(bill.amountMinor).isEqualTo(1600)
        assertThat(bill.nextCharge).isGreaterThan(today)
        sfr.forEach { assertThat(transactions.get(it.transactionId!!)!!.subscriptionId).isEqualTo(bill.id) }
        assertThat(settings.settings.first().ai.bills).isEqualTo(1)

        // A new SFR bill of another amount is linked to it on arrival.
        val next = pay("SFR", 2599, "2026-10-05", BankTxKind.TRANSFER_OUT)
        assertThat(transactions.get(next.transactionId!!)!!.subscriptionId).isEqualTo(bill.id)

        // "Not a bill": gone, and never found again.
        subscriptions.dismissDetected(bill.id)
        server.enqueue(gemini("""[{"key":"sfr","name":"SFR","cadence":"monthly","amount":16.00,"varies":true,"active":true,"confidence":0.95}]"""))
        assistant.run()
        assertThat(subscriptions.all()).isEmpty()
    }

    @Test fun withoutAKeyNothingIsSent() = runTest {
        ready()
        AiKeyStore(tmp.root.resolve("ai.key"), Plain).clear()
        assertThat(assistant.run()).isEqualTo(AiRunResult.NoKey)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun aRefusedKeyIsReported() = runTest {
        ready()
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}""").build())
        val result = assistant.test()
        assertThat(result).isInstanceOf(AiError.BadKey::class.java)
    }
}
