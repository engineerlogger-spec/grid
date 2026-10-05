package com.grid.app.core.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.bank.SecretBox
import com.grid.app.core.bills.LowFundsMonitor
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.prefs.SentLog
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.PendingRepository
import com.grid.app.core.data.repo.PlanRepository
import com.grid.app.core.data.repo.SubscriptionRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.TxType
import com.grid.app.core.money.MoneyFormatter
import com.grid.app.core.notify.Notifier
import com.grid.app.core.time.FixedClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GridAgentTest {

    @get:Rule val tmp = TemporaryFolder()

    private val today = LocalDate.parse("2026-10-05")
    private val clock = FixedClock(today)
    private lateinit var db: GridDatabase
    private lateinit var server: MockWebServer
    private lateinit var transactions: TransactionRepository

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

    private suspend fun TestScope.agent(gemini: GeminiClient): GridAgent {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("s.preferences_pb").also { it.delete() } }, Locale.FRANCE)
        transactions = TransactionRepository(db, clock, emptySet())
        val subscriptions = SubscriptionRepository(db, clock, emptySet())
        val pendings = PendingRepository(db, clock, emptySet())
        val plans = PlanRepository(db, clock, emptySet())
        val bank = BankRepository(db, transactions, clock)
        val formatter = MoneyFormatter(Locale.FRANCE)
        val sentLog = SentLog(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("l.preferences_pb").also { it.delete() } })
        val lowFunds = LowFundsMonitor(settings, bank, subscriptions, pendings, transactions, plans, Notifier(context, formatter), sentLog, clock)
        val tools = AgentTools(context, transactions, com.grid.app.core.data.repo.PaymentTwins(db, transactions, clock, emptySet()), CategoryRepository(db), plans, subscriptions, pendings, settings, formatter, clock)
        return GridAgent(gemini, tools, MoneySnapshots(settings, transactions, subscriptions, bank, plans, lowFunds, clock), clock)
    }

    private fun reply(content: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .body("""{"candidates":[{"content":$content}]}""").build()

    private val addLunch = """{"role":"model","parts":[{"functionCall":{"id":"c1","name":"add_transaction",
        "args":{"type":"expense","amount":12.5,"category":"restaurant","payee":"Berfin","note":"lunch"}},"thoughtSignature":"sig-1"}]}"""

    @Test fun aConfirmedActionIsDoneAndTheConversationGoesOn() = runTest {
        val agent = agent(GeminiClient(OkHttpClient(), { "k" }, listOf("m"), server.url("/").toString()))
        server.enqueue(reply(addLunch))
        server.enqueue(reply("""{"role":"model","parts":[{"text":"Added €12.50 for lunch at Berfin."}]}"""))
        val asked = mutableListOf<AgentStep>()

        val answer = agent.send("Add 12.50 lunch at Berfin", confirm = { asked += it; true }, onStep = { _, _ -> }, onOpenScreen = {})

        assertThat(answer).isEqualTo("Added €12.50 for lunch at Berfin.")
        assertThat(asked.single().summary).contains("Restaurants")
        val added = transactions.observeAll().first().single()
        assertThat(added.merchant).isEqualTo("Berfin")
        assertThat(added.amountMinor).isEqualTo(1250)
        assertThat(added.type).isEqualTo(TxType.EXPENSE)

        server.takeRequest()
        val second = server.takeRequest().body!!.utf8()
        assertThat(second).contains("\"thoughtSignature\":\"sig-1\"") // the model's turn goes back unchanged
        assertThat(second).contains("\"functionResponse\"")
        assertThat(second).contains("\"id\":\"c1\"")
    }

    @Test fun aDeclinedActionChangesNothing() = runTest {
        val agent = agent(GeminiClient(OkHttpClient(), { "k" }, listOf("m"), server.url("/").toString()))
        server.enqueue(reply(addLunch))
        server.enqueue(reply("""{"role":"model","parts":[{"text":"OK, I didn't add it."}]}"""))
        val outcomes = mutableListOf<StepOutcome>()

        agent.send("Add 12.50 lunch at Berfin", confirm = { false }, onStep = { _, o -> outcomes += o }, onOpenScreen = {})

        assertThat(transactions.observeAll().first()).isEmpty()
        assertThat(outcomes).containsExactly(StepOutcome.CANCELLED)
        server.takeRequest()
        assertThat(server.takeRequest().body!!.utf8()).contains("\"cancelled\":true")
    }

    @Test fun readingNeedsNoConfirmationAndScreensOpen() = runTest {
        val agent = agent(GeminiClient(OkHttpClient(), { "k" }, listOf("m"), server.url("/").toString()))
        server.enqueue(reply("""{"role":"model","parts":[{"functionCall":{"name":"open_screen","args":{"screen":"bills"}}}]}"""))
        server.enqueue(reply("""{"role":"model","parts":[{"text":"Here are your bills."}]}"""))
        val opened = mutableListOf<AgentScreen>()
        agent.send("show my bills", confirm = { error("not asked") }, onStep = { _, _ -> }, onOpenScreen = { opened += it })
        assertThat(opened).containsExactly(AgentScreen.BILLS)
    }

    /** Against the real Gemini when a key file is given (GRID_GEMINI_KEY_FILE): proves Google accepts the tools as declared. */
    @Test fun liveGeminiAcceptsTheToolsAndActs() = runTest(timeout = kotlin.time.Duration.parse("2m")) {
        val keyFile = System.getenv("GRID_GEMINI_KEY_FILE")?.let(::File)?.takeIf { it.exists() }
        assumeTrue(keyFile != null)
        val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        val agent = agent(GeminiClient(http, { keyFile!!.readText().trim() }))
        val asked = mutableListOf<AgentStep>()
        val answer = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            agent.send("Add 12.50 for lunch at Berfin today, then open my bills.", confirm = { asked += it; true }, onStep = { _, _ -> }, onOpenScreen = {})
        }
        println("LIVE answer: $answer · confirmed: ${asked.map { it.summary }}")
        assertThat(transactions.observeAll().first().map { it.amountMinor }).contains(1250L)
        assertThat(JsonPrimitive(answer).content).isNotEmpty()
    }
}
