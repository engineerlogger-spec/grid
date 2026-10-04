package com.grid.app.core.bank

import java.io.IOException
import java.time.LocalDate

/** A source of bank data. Enable Banking in production; a canned demo bank in debug builds. */
interface BankConnector {
    suspend fun aspsps(country: String): List<Aspsp>
    suspend fun startAuth(aspsp: Aspsp, validUntil: Long, redirectUrl: String, state: String): AuthStart
    suspend fun createSession(code: String): BankSession
    /** [longest]: everything the bank still offers (the whole history right after the user approves access). */
    suspend fun transactions(accountUid: String, dateFrom: LocalDate?, continuationKey: String?, longest: Boolean = false): TxPage
    suspend fun deleteSession(sessionId: String)
}

sealed class BankError(message: String) : Exception(message) {
    /** The key or application id was refused, or access was revoked. */
    class Unauthorized(val body: String) : BankError("Access refused")
    class SessionExpired : BankError("Bank access expired")
    /** PSD2 allows about four unattended fetches a day per account. */
    class RateLimited : BankError("Daily bank limit reached")
    class Http(val code: Int, val body: String) : BankError("Bank error $code")
    class Network(cause: IOException) : BankError(cause.message ?: "Network error")
}
