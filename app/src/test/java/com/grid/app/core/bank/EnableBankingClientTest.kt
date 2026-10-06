package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

class EnableBankingClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: EnableBankingClient

    private val pem: String = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded.let {
        "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(it) + "\n-----END PRIVATE KEY-----"
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = EnableBankingClient(OkHttpClient(), { BankCredentials("app-42", pem) }, { 1_790_000_000 }, server.url("/"))
    }

    @After fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test fun listsBanksWithASignedJwt() = runTest {
        server.enqueue(json("""{"aspsps":[{"name":"Revolut","country":"FR","maximum_consent_validity":15552000,"psu_types":["personal"]},{"name":"BNP"}]}"""))
        val banks = client.aspsps("FR")
        assertThat(banks).containsExactly(Aspsp("Revolut", "FR", 15552000), Aspsp("BNP", "FR", null)).inOrder()

        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/aspsps")
        assertThat(request.url.queryParameter("country")).isEqualTo("FR")
        assertThat(request.url.queryParameter("psu_type")).isEqualTo("personal")
        val jwt = request.headers["Authorization"]!!.removePrefix("Bearer ")
        assertThat(jwt.split('.')).hasSize(3)
        val header = Json.parseToJsonElement(Base64.getUrlDecoder().decode(jwt.split('.')[0]).decodeToString()).jsonObject
        assertThat(header["kid"]!!.jsonPrimitive.content).isEqualTo("app-42")
    }

    @Test fun asksAsThePersonInTheAppWhenPresent() = runTest {
        server.enqueue(json("""{"balances":[]}"""))
        server.enqueue(json("""{"balances":[]}"""))
        client.balances("u-1")
        client.present(Presence("203.0.113.7", "Grid/test")).balances("u-1")
        val background = server.takeRequest()
        assertThat(background.headers["Psu-Ip-Address"]).isNull()
        val present = server.takeRequest()
        assertThat(present.headers["Psu-Ip-Address"]).isEqualTo("203.0.113.7")
        assertThat(present.headers["Psu-User-Agent"]).isEqualTo("Grid/test")
        assertThat(present.headers["Authorization"]).startsWith("Bearer ")
    }

    @Test fun startsAuthorisation() = runTest {
        server.enqueue(json("""{"url":"https://auth.example/xyz","authorization_id":"auth-1"}"""))
        val validUntil = Instant.parse("2027-04-02T10:00:00Z").toEpochMilli()
        val start = client.startAuth(Aspsp("Revolut", "FR", null), validUntil, "https://example.com/cb/", "st-1")
        assertThat(start).isEqualTo(AuthStart("https://auth.example/xyz", "auth-1"))

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/auth")
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertThat(body["access"]!!.jsonObject["valid_until"]!!.jsonPrimitive.content).isEqualTo("2027-04-02T10:00:00Z")
        assertThat(body["aspsp"]!!.jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("Revolut")
        assertThat(body["state"]!!.jsonPrimitive.content).isEqualTo("st-1")
        assertThat(body["redirect_url"]!!.jsonPrimitive.content).isEqualTo("https://example.com/cb/")
        assertThat(body["psu_type"]!!.jsonPrimitive.content).isEqualTo("personal")
    }

    @Test fun createsSessionWithAccounts() = runTest {
        server.enqueue(
            json(
                """{"session_id":"s-1","access":{"valid_until":"2027-04-02T10:00:00.000000+00:00"},
                   "accounts":[{"uid":"u-1","identification_hash":"h-1","currency":"EUR","name":"Main","account_id":{"iban":"LT123250000000000001"}},
                               {"uid":"u-2","identification_hash":"h-2","currency":"USD"}]}""",
            ),
        )
        val session = client.createSession("code-9")
        assertThat(session.sessionId).isEqualTo("s-1")
        assertThat(session.validUntil).isEqualTo(Instant.parse("2027-04-02T10:00:00Z").toEpochMilli())
        assertThat(session.accounts).containsExactly(
            RemoteAccount("u-1", "h-1", "EUR", "Main", "LT123250000000000001"),
            RemoteAccount("u-2", "h-2", "USD", null, null),
        ).inOrder()
        assertThat(Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["code"]!!.jsonPrimitive.content).isEqualTo("code-9")
    }

    @Test fun pagesAndMapsTransactions() = runTest {
        server.enqueue(
            json(
                """{"continuation_key":"next-1","transactions":[
                   {"transaction_id":"t-1","transaction_amount":{"currency":"EUR","amount":"4.50"},"credit_debit_indicator":"DBIT","status":"BOOK",
                    "booking_date":"2026-10-03","transaction_date":"2026-10-02","creditor":{"name":"Starbucks"},"merchant_category_code":"5814",
                    "remittance_information":["Starbucks Paris"]},
                   {"entry_reference":"e-2","transaction_amount":{"currency":"EUR","amount":"850.00"},"credit_debit_indicator":"DBIT","status":"BOOK",
                    "booking_date":"2026-10-01","creditor":{"name":"J. Dupont"},"creditor_account":{"iban":"FR7630006000011234567890189"},
                    "bank_transaction_code":{"code":"ICDT","description":"Transfer"},"remittance_information":["To J. Dupont"]},
                   {"transaction_amount":{"currency":"EUR","amount":"2500.00"},"credit_debit_indicator":"CRDT","status":"BOOK",
                    "booking_date":"2026-09-28","debtor":{"name":"ACME SAS"},"debtor_account":{"iban":"FR7699999"},"transaction_id":null}
                   ]}""",
            ),
        )
        val page = client.transactions("u-1", LocalDate.parse("2026-09-01"), null)
        assertThat(page.continuationKey).isEqualTo("next-1")
        val (card, transfer, credit) = page.transactions
        assertThat(card.transactionId).isEqualTo("t-1")
        assertThat(card.amount).isEqualTo("4.50")
        assertThat(card.counterparty).isEqualTo("Starbucks")
        assertThat(card.mcc).isEqualTo("5814")
        assertThat(card.transactionDate).isEqualTo("2026-10-02")
        assertThat(card.rawJson).contains("\"t-1\"")
        assertThat(transfer.entryReference).isEqualTo("e-2")
        assertThat(transfer.creditorIban).isEqualTo("FR7630006000011234567890189")
        assertThat(transfer.bankTxCode).isEqualTo("ICDT Transfer")
        assertThat(credit.isCredit).isTrue()
        assertThat(credit.transactionId).isNull()
        assertThat(credit.counterparty).isEqualTo("ACME SAS")

        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/accounts/u-1/transactions")
        assertThat(request.url.queryParameter("date_from")).isEqualTo("2026-09-01")

        server.enqueue(json("""{"transactions":[]}"""))
        assertThat(client.transactions("u-1", LocalDate.parse("2026-09-01"), "next-1").continuationKey).isNull()
        assertThat(server.takeRequest().url.queryParameter("continuation_key")).isEqualTo("next-1")
    }

    @Test fun asksForTheWholeHistory() = runTest {
        server.enqueue(json("""{"transactions":[]}"""))
        client.transactions("u-1", null, null, longest = true)
        val request = server.takeRequest()
        assertThat(request.url.queryParameter("strategy")).isEqualTo("longest")
        assertThat(request.url.queryParameter("date_from")).isNull()
    }

    @Test fun deletesSession() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())
        client.deleteSession("s-1")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.url.encodedPath).isEqualTo("/sessions/s-1")
    }

    @Test fun unusableKeyIsAnAccessError() = runTest {
        val broken = EnableBankingClient(OkHttpClient(), { BankCredentials("app-42", "not a key") }, { 1_790_000_000 }, server.url("/"))
        assertThat(runCatching { broken.aspsps("FR") }.exceptionOrNull()).isInstanceOf(BankError.Unauthorized::class.java)
    }

    @Test fun garbledResponseIsABankError() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("<html>maintenance</html>").build())
        assertThat((runCatching { client.aspsps("FR") }.exceptionOrNull() as BankError.Http).code).isEqualTo(200)
    }

    @Test fun errorsAreTyped() = runTest {
        suspend fun errorFor(response: MockResponse): Throwable? {
            server.enqueue(response)
            return runCatching { client.aspsps("FR") }.exceptionOrNull()
        }
        assertThat(errorFor(json("""{"error":"UNAUTHORIZED"}""", 401))).isInstanceOf(BankError.Unauthorized::class.java)
        assertThat(errorFor(json("""{"error":"EXPIRED_SESSION"}""", 401))).isInstanceOf(BankError.SessionExpired::class.java)
        assertThat(errorFor(json("""{"error":"TOO_MANY"}""", 429))).isInstanceOf(BankError.RateLimited::class.java)
        assertThat((errorFor(json("{}", 503)) as BankError.Http).code).isEqualTo(503)
    }
}
