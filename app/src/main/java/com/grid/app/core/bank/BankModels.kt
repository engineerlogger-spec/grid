package com.grid.app.core.bank

/** A bank reachable through the aggregator ("ASPSP" in PSD2 terms). */
data class Aspsp(val name: String, val country: String, val maxConsentSeconds: Long?)

data class AuthStart(val url: String, val authorizationId: String?)

data class RemoteAccount(val uid: String, val identificationHash: String, val currency: String, val name: String?, val iban: String?)

data class BankSession(val sessionId: String, val validUntil: Long?, val accounts: List<RemoteAccount>)

/** One transaction as the bank reports it; every field is optional because banks fill them differently. */
data class RemoteTx(
    val transactionId: String? = null,
    val entryReference: String? = null,
    val amount: String,
    val currency: String,
    /** "DBIT" (money out) or "CRDT" (money in). */
    val creditDebit: String? = null,
    /** "BOOK" (settled) or "PDNG" (pending). */
    val status: String? = null,
    val bookingDate: String? = null,
    val valueDate: String? = null,
    val transactionDate: String? = null,
    val creditorName: String? = null,
    val creditorIban: String? = null,
    val debtorName: String? = null,
    val debtorIban: String? = null,
    val remittance: List<String> = emptyList(),
    val mcc: String? = null,
    val bankTxCode: String? = null,
    val rawJson: String = "{}",
) {
    /** Money in. Without an indicator, a signed amount tells ("-12.50" is money out). */
    val isCredit: Boolean
        get() = when {
            creditDebit.equals("CRDT", ignoreCase = true) -> true
            creditDebit.equals("DBIT", ignoreCase = true) -> false
            else -> !amount.trim().startsWith("-")
        }

    /** Who the money went to (or came from), falling back to the first remittance line. */
    val counterparty: String?
        get() = (if (isCredit) debtorName else creditorName)?.takeIf { it.isNotBlank() } ?: remittance.firstOrNull { it.isNotBlank() }

    val counterpartyIban: String? get() = if (isCredit) debtorIban else creditorIban

    /** Card payments Revolut hasn't settled yet (usually the last day or two). */
    val isPending: Boolean get() = status.equals("PDNG", ignoreCase = true)

    val isBooked: Boolean get() = status == null || status.equals("BOOK", ignoreCase = true)
}

data class TxPage(val transactions: List<RemoteTx>, val continuationKey: String?)

/** One balance of an account; [type] is the ISO 20022 code (ITAV interim available, CLBD closing booked…). */
data class RemoteBalance(val amount: String, val currency: String, val type: String?, val creditDebit: String? = null) {
    companion object {
        /** What can be spent now first, then the booked balance. */
        private val PREFERENCE = listOf("ITAV", "CLAV", "XPCD", "ITBD", "CLBD", "OPBD")

        fun pick(balances: List<RemoteBalance>, currency: String): RemoteBalance? =
            balances.filter { it.currency == currency }.minByOrNull { b -> PREFERENCE.indexOf(b.type?.uppercase()).let { if (it < 0) PREFERENCE.size else it } }
    }

    /** Signed minor units: negative when overdrawn ("-12.00" or a DBIT indicator). */
    fun minor(): Long? {
        val raw = amount.trim()
        val value = runCatching { com.grid.app.core.money.Currencies.toMinor(raw.removePrefix("-").removePrefix("+"), currency) }.getOrNull() ?: return null
        return if (raw.startsWith("-") || creditDebit.equals("DBIT", ignoreCase = true)) -value else value
    }
}
