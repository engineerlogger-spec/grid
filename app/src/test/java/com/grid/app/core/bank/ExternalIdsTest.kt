package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExternalIdsTest {
    private fun tx(id: String? = null, ref: String? = null, name: String = "Coffee") =
        RemoteTx(transactionId = id, entryReference = ref, amount = "3.50", currency = "EUR", creditDebit = "DBIT", bookingDate = "2026-10-04", creditorName = name)

    @Test fun bankIdsArePreferred() {
        val ids = ExternalIds.assign(listOf(tx(id = "t-1"), tx(ref = "e-2"))).map { it.first }
        assertThat(ids).containsExactly("t-1", "e-2").inOrder()
    }

    @Test fun identicalTwinsGetDistinctStableIds() {
        val first = ExternalIds.assign(listOf(tx(), tx(), tx(name = "Tea"))).map { it.first }
        assertThat(first[0]).endsWith("#0")
        assertThat(first[1]).endsWith("#1")
        assertThat(first[0].substringBefore('#')).isEqualTo(first[1].substringBefore('#'))
        assertThat(first[2]).endsWith("#0")
        assertThat(first.toSet()).hasSize(3)
        assertThat(ExternalIds.assign(listOf(tx(), tx(), tx(name = "Tea"))).map { it.first }).isEqualTo(first)
    }
}
