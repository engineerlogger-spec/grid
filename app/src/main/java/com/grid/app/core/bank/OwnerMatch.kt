package com.grid.app.core.bank

import com.grid.app.core.model.MerchantKey

/**
 * Is this counterparty the account holder themself? Revolut reports the holder's name as the account name, and
 * money moved from the holder's other banks arrives under that name in any order ("MOULOUD ABDELHAMID",
 * "M ABDELHAMID MOULOUD") or as a saved payee like "Abdelhamid N26".
 */
object OwnerMatch {
    private val banks = setOf(
        "n26", "bnp", "paribas", "revolut", "lcl", "sg", "societe", "generale", "boursorama", "credit", "agricole", "caisse",
        "epargne", "postale", "banque", "hello", "fortuneo", "ing", "bforbank", "monabanq", "qonto", "wise", "lydia", "sumeria",
        "shine", "nickel", "cic", "mutuel", "hsbc", "axa", "bank", "compte", "account", "livret",
    )

    fun isOwner(counterparty: String?, ownerNames: List<String>): Boolean {
        val tokens = tokens(counterparty)
        if (tokens.isEmpty()) return false
        return ownerNames.any { owner ->
            val names = tokens(owner).filter { it.length >= 3 }.toSet()
            names.isNotEmpty() && (tokens.containsAll(names) || (tokens.any { it in names } && tokens.any { it in banks }))
        }
    }

    private fun tokens(text: String?): Set<String> = text?.let(MerchantKey::of)?.split(' ')?.toSet().orEmpty()
}
