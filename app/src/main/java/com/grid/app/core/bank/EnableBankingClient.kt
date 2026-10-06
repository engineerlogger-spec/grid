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
    /** Set: every request says the person is in the app (PSU headers), so the bank doesn't count it as background. */
    private val presence: Presence? = null,
) : BankConnector {

    /** The same client, asking as the person in the app. */
    fun present(presence: Presence) = EnableBankingClient(http, credentials, nowSeconds, base, presence)

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

    /**
     * One page of an account's transactions. With [longest], Enable Banking returns everything the bank still offers
     * (right after the user approves access, that is the whole history) and [dateFrom] is ignored.
     */
    override suspend fun transactions(accountUid: String, dateFrom: LocalDate?, continuationKey: String?, longest: Boolean): TxPage {
        val url = base.newBuilder().addPathSegment("accounts").addPathSegment(accountUid).addPathSegment("transactions")
            .apply {
                if (longest) addQueryParameter("strategy", "longest") else dateFrom?.let { addQueryParameter("date_from", it.toString()) }
                continuationKey?.let { addQueryParameter("continuation_key", it) }
            }
            .build()
        val body = call(Request.Builder().url(url).get())
        return TxPage(body.array("transactions").map { RemoteTxJson.from(it.jsonObject) }, body.str("continuation_key")?.takeIf { it.isNotBlank() })
    }

    override suspend fun balances(accountUid: String): List<RemoteBalance> {
        val url = base.newBuilder().addPathSegment("accounts").addPathSegment(accountUid).addPathSegment("balances").build()
        return call(Request.Builder().url(url).get()).array("balances").map { it.jsonObject }.mapNotNull { b ->
            val amount = b.obj("balance_amount") ?: return@mapNotNull null
            RemoteBalance(
                amount = amount.str("amount") ?: return@mapNotNull null, currency = amount.str("currency") ?: "EUR",
                type = b.str("balance_type"), creditDebit = b.str("credit_debit_indicator"),
            )
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        call(Request.Builder().url(base.newBuilder().addPathSegment("sessions").addPathSegment(sessionId).build()).delete())
    }

    private suspend fun authorization(): String {
        val creds = credentials()
        val key = cachedKey?.takeIf { it.first == creds }?.second
            ?: runCatching { PemKeys.parsePrivateKey(creds.privateKeyPem) }.getOrElse { throw BankError.Unauthorized("Unusable private key") }
                .also { cachedKey = creds to it }
        return "Bearer " + EnableBankingJwt.sign(creds.appId, key, nowSeconds())
    }

    private suspend fun call(builder: Request.Builder): JsonObject {
        val request = builder.header("Authorization", authorization()).header("Accept", "application/json")
            .apply { presence?.let { header("Psu-Ip-Address", it.ipAddress).header("Psu-User-Agent", it.userAgent) } }
            .build()
        return withContext(Dispatchers.IO) {
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw BankError.Network(e)
            }
            response.use {
                val text = it.body.string()
                when {
                    it.isSuccessful -> if (text.isBlank()) JsonObject(emptyMap()) else {
                        // A maintenance page or proxy error instead of JSON is a bank error, not a crash.
                        runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { _ -> throw BankError.Http(it.code, text.take(200)) }
                    }
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
