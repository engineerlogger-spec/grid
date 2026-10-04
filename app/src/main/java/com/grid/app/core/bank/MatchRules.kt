package com.grid.app.core.bank

import com.grid.app.core.model.BankTxKind
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import kotlin.math.abs

/** The settled bank transaction being reconciled. */
data class BankFacts(val type: TxType, val kind: BankTxKind, val amountMinor: Long, val occurredAt: Long, val merchant: String?)

/** A ledger entry that might be the same money (a notification capture, a manual entry, a logged bill…). */
data class Candidate(val id: Long, val source: TxSource, val amountMinor: Long, val occurredAt: Long, val merchant: String?)

/**
 * When is a bank transaction the same money as something already in the ledger? Rules per source:
 * captures and manual entries are recorded around the moment of payment and the bank books later;
 * bills (subscriptions, pending payments) are recorded on their due date and may move a few days.
 */
object MatchRules {
    private const val DAY = 86_400_000L

    /** Amount-only bill matching is allowed for these (they name a person, not the bill). */
    fun isTransfer(kind: BankTxKind) = kind == BankTxKind.TRANSFER_OUT || kind == BankTxKind.MONEY_IN

    fun relativeDiff(bank: Long, other: Long): Double = if (bank == 0L) Double.MAX_VALUE else abs(bank - other).toDouble() / bank

    fun similar(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return true
        val left = tokens(a)
        val right = tokens(b)
        return left.any { it.length >= 3 && it in right }
    }

    fun bestMatch(bank: BankFacts, candidates: List<Candidate>): Candidate? = candidates
        .filter { eligible(bank, it) }
        .minWithOrNull(compareBy<Candidate>({ relativeDiff(bank.amountMinor, it.amountMinor) }, { abs(it.occurredAt - bank.occurredAt) }, { it.id }))

    private fun eligible(bank: BankFacts, c: Candidate): Boolean {
        val days = (c.occurredAt - bank.occurredAt).toDouble() / DAY
        val diff = relativeDiff(bank.amountMinor, c.amountMinor)
        val named = similar(bank.merchant, c.merchant)
        return when (c.source) {
            TxSource.CAPTURE, TxSource.MANUAL -> days in -5.0..1.0 && named && diff <= 0.05
            // Transfers name a person ("J. Dupont"), not the bill ("Rent"), so they may match on amount alone;
            // card payments must name the same merchant (a €13.49 café bill is not Netflix).
            TxSource.SUBSCRIPTION, TxSource.PENDING -> abs(days) <= 3.0 && when {
                named && diff <= 0.10 -> true
                isTransfer(bank.kind) -> diff <= 0.02
                else -> false
            }
            TxSource.CHECKIN -> bank.type == TxType.INCOME && abs(days) <= 7.0 && diff <= 0.10
            TxSource.BANK -> false
        }
    }

    private fun tokens(text: String): Set<String> = MerchantKey.of(text)?.split(' ')?.toSet().orEmpty()
}
