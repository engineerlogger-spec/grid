package com.grid.app.core.bank

import com.grid.app.core.data.repo.BankRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** How one kind of data answered: [answered] requests before the bank refused one ([refused]), or an [error]. */
data class EndpointLimit(val answered: Int, val refused: Boolean, val error: String? = null)

/**
 * What the limit test measured. [details]: data Grid's syncs never ask for, so its count starts from zero today.
 * [balance]: asked once per sync, so its count is what today's syncs left. [present]: balance requests made as the
 * person in the app, after the refusals ([presentError]: why they failed, or no internet address was found).
 */
data class LimitReport(
    val details: EndpointLimit,
    val balance: EndpointLimit,
    val presentAnswered: Int,
    val presentTried: Int,
    val presentError: String?,
)

/**
 * Measures the bank's daily limit for background access on the user's own account: asks until it refuses, at most
 * [MAX] times per kind of data, then asks again as the person in the app. Payments are never asked for, so the test
 * leaves Grid's own syncs their daily allowance (only the balance may stay as it is until tomorrow).
 */
@Singleton
class BankLimitProbe @Inject constructor(
    private val bank: BankRepository,
    private val connectors: BankConnectorProvider,
    private val presence: PresenceProvider,
) {
    /** Null without a real bank connection (no account, or the demo bank). */
    suspend fun run(): LimitReport? {
        val client = connectors.current() as? EnableBankingClient ?: return null
        val uid = bank.accounts().firstOrNull { it.enabled }?.uid ?: return null
        val details = untilRefused { client.details(uid) }
        val balance = untilRefused { client.balances(uid) }
        val asPerson = presence.now()?.let(client::present)
            ?: return LimitReport(details, balance, 0, 0, NO_ADDRESS)
        var answered = 0
        var error: String? = null
        for (n in 1..PRESENT_TRIES) {
            try {
                asPerson.balances(uid)
                answered++
            } catch (e: BankError) {
                error = e.message
                break
            }
        }
        return LimitReport(details, balance, answered, PRESENT_TRIES, error)
    }

    private suspend fun untilRefused(request: suspend () -> Unit): EndpointLimit {
        for (n in 1..MAX) {
            try {
                request()
            } catch (e: BankError.RateLimited) {
                return EndpointLimit(n - 1, refused = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BankError) {
                return EndpointLimit(n - 1, refused = false, error = e.message)
            }
        }
        return EndpointLimit(MAX, refused = false)
    }

    companion object {
        /** Well past PSD2's four: answering all of them means the bank is more generous. */
        const val MAX = 12
        const val PRESENT_TRIES = 3
        const val NO_ADDRESS = "no internet address"
    }
}
