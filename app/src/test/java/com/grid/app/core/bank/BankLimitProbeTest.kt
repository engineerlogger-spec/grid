package com.grid.app.core.bank

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.time.FixedClock
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.time.LocalDate
import java.util.Base64

/** The daily limit test against a bank that answers a few background requests per kind of data, and anyone present. */
@RunWith(AndroidJUnit4::class)
class BankLimitProbeTest {

    private val clock = FixedClock(LocalDate.parse("2026-10-06"))
    private lateinit var db: GridDatabase
    private lateinit var server: MockWebServer
    private val pem: String = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded.let {
        "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(it) + "\n-----END PRIVATE KEY-----"
    }

    /** Background allowance left per kind of data; requests carrying the person's address are never counted. */
    private val left = mutableMapOf("details" to 4, "balances" to 2)
    private val seenAddresses = mutableListOf<String?>()

    @Before fun setUp() {
        db = GridDatabase.inMemory(ApplicationProvider.getApplicationContext())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path == "/ip") return MockResponse.Builder().body("203.0.113.7").build()
                val kind = path.substringAfterLast('/')
                val address = request.headers["Psu-Ip-Address"]
                seenAddresses += address
                if (address == null) {
                    val remaining = left.getValue(kind)
                    if (remaining == 0) return MockResponse.Builder().code(429).body("""{"code":429,"error":"ASPSP_RATE_LIMIT_EXCEEDED"}""").build()
                    left[kind] = remaining - 1
                }
                val body = if (kind == "balances") """{"balances":[{"balance_amount":{"currency":"EUR","amount":"152.48"},"balance_type":"ITAV"}]}""" else """{"uid":"u-1"}"""
                return MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
    }

    @After fun tearDown() {
        server.close()
        db.close()
    }

    private suspend fun probe(): BankLimitProbe {
        val transactions = TransactionRepository(db, clock, emptySet())
        val bank = BankRepository(db, transactions, clock)
        bank.beginAuth(Aspsp("Revolut", "FR", null), "st", null)
        bank.completeAuth("st", BankSession("s", null, listOf(RemoteAccount("u-1", "h", "EUR", null, null))), "EUR")
        val client = EnableBankingClient(OkHttpClient(), { BankCredentials("app", pem) }, { 1_790_000_000 }, server.url("/"))
        return BankLimitProbe(bank, { client }, PresenceProvider(OkHttpClient(), clock, server.url("/ip").toString()))
    }

    @Test fun countsWhatTheBankAnswersBeforeRefusingThenAsksAsThePerson() = runTest {
        val report = probe().run()!!
        assertThat(report.details).isEqualTo(EndpointLimit(answered = 4, refused = true))
        assertThat(report.balance).isEqualTo(EndpointLimit(answered = 2, refused = true))
        assertThat(report.presentAnswered).isEqualTo(BankLimitProbe.PRESENT_TRIES)
        assertThat(report.presentError).isNull()
        // Background requests carry no address; the last ones carry the phone's.
        assertThat(seenAddresses.takeLast(BankLimitProbe.PRESENT_TRIES)).containsExactly("203.0.113.7", "203.0.113.7", "203.0.113.7")
        assertThat(seenAddresses.dropLast(BankLimitProbe.PRESENT_TRIES).toSet()).containsExactly(null)
    }

    @Test fun aGenerousBankAnswersEverything() = runTest {
        left["details"] = 100
        left["balances"] = 100
        val report = probe().run()!!
        assertThat(report.details).isEqualTo(EndpointLimit(answered = BankLimitProbe.MAX, refused = false))
        assertThat(report.balance.refused).isFalse()
    }
}
