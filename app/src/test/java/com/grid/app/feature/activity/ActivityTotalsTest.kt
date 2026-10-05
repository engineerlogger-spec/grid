package com.grid.app.feature.activity

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import org.junit.Test

class ActivityTotalsTest {

    private val category = Category(1, "Other", "other", "slate", CategoryKind.EXPENSE, 0)
    private fun tx(type: TxType, amount: Long, own: Boolean = false) = Transaction(
        id = 0, type = type, amountMinor = amount, currency = "EUR", category = category, method = null, merchant = null,
        note = null, occurredAt = 0, createdAt = 0, source = TxSource.BANK, ownTransfer = own,
    )

    private val month = listOf(
        tx(TxType.INCOME, 150_000, own = true), // moved in from the salary bank
        tx(TxType.INCOME, 50_000, own = true),
        tx(TxType.EXPENSE, 20_000, own = true), // sent back
        tx(TxType.INCOME, 3_000),               // a refund
        tx(TxType.EXPENSE, 45_000),             // rent
    )

    @Test fun incomeIsEverythingThatCameIn() {
        assertThat(ActivityViewModel.incomeOf(month)).isEqualTo(203_000)
    }

    @Test fun spentIsEverythingThatWentOutEvenToOwnAccounts() {
        assertThat(ActivityViewModel.spentOf(month)).isEqualTo(65_000)
    }
}
