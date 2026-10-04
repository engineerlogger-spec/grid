package com.grid.app.core.bank

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.PrivateKey
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Enable Banking's account-information API (https://enablebanking.com/docs/api/reference/), called straight
 * from the phone: every request carries a JWT signed with the user's own application key.
 */
class EnableBankingClient(
    private val http: OkHttpClient,
    private val credentials: suspend () -> BankCredentials,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val base: HttpUrl = "https://api.enablebanking.com/".toHttpUrl(),
) : BankConnector {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()
    private var cachedKey: Pair<BankCredentials, PrivateKey>? = null

    override suspend fun aspsps(country: String): List<Aspsp> {
        val url = base.newBuilder().addPathSegment("aspsps").addQueryParameter("country", country).addQueryParameter("psu_type", "personal").build()
        val body = call(Request.Builder().url(url).get())
        return body.array("aspsps").map { it.jsonObject }.mapNotNull { a ->
            val name = a.str("name") ?: return@mapNotNull null
            Aspsp(name, a.str("country") ?: country, (a["maximum_consent_validity"] as? JsonPrimitive)?.longOrNull)
        }
    }

    override suspend fun startAuth(aspsp: Aspsp, validUntil: Long, redirectUrl: String, state: String): AuthStart {
        val payload = buildJsonObject {
            putJsonObject("access") { put("valid_until", Instant.ofEpochMilli(validUntil).toString()) }
            putJsonObject("aspsp") { put("name", aspsp.name); put("country", aspsp.country) }
            put("state", state)
            put("redirect_url", redirectUrl)
            put("psu_type", "personal")
        }
        val body = call(Request.Builder().url(base.newBuilder().addPathSegment("auth").build()).post(payload.toString().toRequestBody(jsonType)))
        return AuthStart(body.str("url") ?: throw BankError.Http(200, "No authorisation URL"), body.str("authorization_id"))
    }

    override suspend fun createSession(code: String): BankSession {
        val payload = buildJsonObject { put("code", code) }
        val body = call(Request.Builder().url(base.newBuilder().addPathSegment("sessions").build()).post(payload.toString().toRequestBody(jsonType)))
        val accounts = body.array("accounts").map { it.jsonObject }.mapNotNull { a ->
            val uid = a.str("uid") ?: return@mapNotNull null
            RemoteAccount(
                uid = uid,
                identificationHash = a.str("identification_hash") ?: uid,
                currency = a.str("currency") ?: "EUR",
                name = a.str("name") ?: a.str("product"),
                iban = a.obj("account_id")?.str("iban"),
            )
        }
        return BankSession(
            sessionId = body.str("session_id") ?: throw BankError.Http(200, "No session id"),
            validUntil = body.obj("access")?.str("valid_until")?.let(::parseInstant),
            accounts = accounts,
        )
    }

    override suspend fun transactions(accountUid: String, dateFrom: LocalDate, continuationKey: String?): TxPage {
        val url = base.newBuilder().addPathSegment("accounts").addPathSegment(accountUid).addPathSegment("transactions")
            .addQueryParameter("date_from", dateFrom.toString())
            .apply { continuationKey?.let { addQueryParameter("continuation_key", it) } }
            .build()
        val body = call(Request.Builder().url(url).get())
        return TxPage(body.array("transactions").map { it.jsonObject.toRemoteTx() }, body.str("continuation_key")?.takeIf { it.isNotBlank() })
    }

    override suspend fun deleteSession(sessionId: String) {
        call(Request.Builder().url(base.newBuilder().addPathSegment("sessions").addPathSegment(sessionId).build()).delete())
    }

    private fun JsonObject.toRemoteTx(): RemoteTx {
        val amount = obj("transaction_amount")
        val code = obj("bank_transaction_code")
        return RemoteTx(
            transactionId = str("transaction_id"),
            entryReference = str("entry_reference"),
            amount = amount?.str("amount") ?: "0",
            currency = amount?.str("currency") ?: "EUR",
            creditDebit = str("credit_debit_indicator"),
            status = str("status"),
            bookingDate = str("booking_date"),
            valueDate = str("value_date"),
            transactionDate = str("transaction_date"),
            creditorName = obj("creditor")?.str("name"),
            creditorIban = obj("creditor_account")?.str("iban"),
            debtorName = obj("debtor")?.str("name"),
            debtorIban = obj("debtor_account")?.str("iban"),
            remittance = (this["remittance_information"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            mcc = str("merchant_category_code"),
            bankTxCode = listOfNotNull(code?.str("code"), code?.str("sub_code"), code?.str("description")).joinToString(" ").ifBlank { null },
            rawJson = toString(),
        )
    }

    private suspend fun authorization(): String {
        val creds = credentials()
        val key = cachedKey?.takeIf { it.first == creds }?.second ?: PemKeys.parsePrivateKey(creds.privateKeyPem).also { cachedKey = creds to it }
        return "Bearer " + EnableBankingJwt.sign(creds.appId, key, nowSeconds())
    }

    private suspend fun call(builder: Request.Builder): JsonObject {
        val request = builder.header("Authorization", authorization()).header("Accept", "application/json").build()
        return withContext(Dispatchers.IO) {
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw BankError.Network(e)
            }
            response.use {
                val text = it.body.string()
                when {
                    it.isSuccessful -> if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
                    text.contains("EXPIRED_SESSION") || text.contains("SESSION_EXPIRED") -> throw BankError.SessionExpired()
                    it.code == 401 || it.code == 403 -> throw BankError.Unauthorized(text)
                    it.code == 429 -> throw BankError.RateLimited()
                    else -> throw BankError.Http(it.code, text)
                }
            }
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.array(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())

    private fun parseInstant(text: String): Long? =
        runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull() ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}
