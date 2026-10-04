package com.grid.app.core.bank

import com.grid.app.core.model.BankTxKind

/**
 * Decides what a bank transaction is. Card payments and direct debits are spending; moves between the user's
 * own pockets, vaults, exchanges and top-ups are not money spent at all. Revolut wording is matched in English,
 * the language its Open Banking API reports in.
 */
object TxClassifier {
    private val internal = listOf(
        Regex("\\b(to|from) (\\w+ )?(vault|pocket|savings)\\b"),
        Regex("\\bexchanged? (to|from)\\b"),
        Regex("\\bflexible (cash )?funds?\\b"),
        Regex("\\b(stocks?|crypto|commodities)\\b"),
        Regex("\\binvestment account\\b"),
    )
    /** Money added from another account's card: moved from the user's own money, like a transfer from their salary bank. */
    private val topUp = Regex("\\btop[- ]?up\\b")
    private val directDebitText =Regex("\\b(direct debit|sepa dd|pr[ée]l[èe]vement|mandate)\\b")
    private val directDebitCode = Regex("\\b(dd|pmdd|ddt|direct debit)\\b", RegexOption.IGNORE_CASE)
    private val transferCode = Regex("\\b(trf|icdt|rcdt|transfer)\\b", RegexOption.IGNORE_CASE)
    private val toPerson = Regex("^to [\\p{L} .'-]+$", RegexOption.IGNORE_CASE)

    fun classify(tx: RemoteTx, ownIbans: Set<String>): BankTxKind {
        val counterpartyIban = tx.counterpartyIban?.let(::normalizeIban)
        if (counterpartyIban != null && counterpartyIban in ownIbans.map(::normalizeIban)) return BankTxKind.INTERNAL

        val text = (listOfNotNull(if (tx.isCredit) tx.debtorName else tx.creditorName) + tx.remittance).joinToString(" ").lowercase()
        if (tx.isCredit && topUp.containsMatchIn(text)) return BankTxKind.TOP_UP
        if (internal.any { it.containsMatchIn(text) }) return BankTxKind.INTERNAL
        if (tx.isCredit) return BankTxKind.MONEY_IN

        val code = tx.bankTxCode.orEmpty()
        if (directDebitCode.containsMatchIn(code) || directDebitText.containsMatchIn(text)) return BankTxKind.DIRECT_DEBIT
        if (tx.creditorIban != null || transferCode.containsMatchIn(code)) return BankTxKind.TRANSFER_OUT
        if (tx.remittance.firstOrNull()?.trim()?.let(toPerson::matches) == true) return BankTxKind.TRANSFER_OUT
        return BankTxKind.CARD_SPEND
    }

    fun normalizeIban(iban: String): String = iban.filterNot(Char::isWhitespace).uppercase()
}
