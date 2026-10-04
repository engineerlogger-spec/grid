package com.grid.app.core.bank

import java.security.MessageDigest

/**
 * A stable id per bank transaction so re-fetching overlapping date ranges never duplicates anything.
 * Banks usually send one; otherwise the content is hashed and identical twins (two €3.50 coffees on the same
 * day) are told apart by their order.
 */
object ExternalIds {
    fun assign(transactions: List<RemoteTx>): List<Pair<String, RemoteTx>> {
        val seen = mutableMapOf<String, Int>()
        return transactions.map { tx ->
            val given = tx.transactionId?.takeIf { it.isNotBlank() } ?: tx.entryReference?.takeIf { it.isNotBlank() }
            val id = given ?: run {
                val hash = sha1(listOf(tx.bookingDate, tx.amount, tx.creditDebit, tx.counterparty, tx.remittance.joinToString("|")).joinToString("|"))
                val n = seen.getOrDefault(hash, 0)
                seen[hash] = n + 1
                "h:$hash#$n"
            }
            id to tx
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
