package com.grid.app.feature.spending

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import org.junit.Test

class SpendingBreakdownTest {

    private val groceries = Category(1, "Groceries", "groceries", "green", CategoryKind.EXPENSE, 0)
    private val rent = Category(2, "Housing", "housing", "blue", CategoryKind.EXPENSE, 1)
    private val salary = Category(3, "Salary", "salary", "green", CategoryKind.INCOME, 0)

    private fun tx(category: Category, amount: Long, type: TxType = TxType.EXPENSE, source: TxSource = TxSource.BANK) = Transaction(
        id = 0, type = type, amountMinor = amount, currency = "EUR", category = category, method = null, merchant = null,
        note = null, occurredAt = 0, createdAt = 0, source = source,
    )

    @Test fun categoriesBiggestFirstWithShareCountAndLastMonth() {
        val october = listOf(tx(groceries, 2_000), tx(groceries, 3_000), tx(rent, 15_000), tx(salary, 300_000, TxType.INCOME))
        val september = listOf(tx(groceries, 4_000))
        val rows = SpendingViewModel.breakdown(october, september)
        assertThat(rows.map { it.category.name }).containsExactly("Housing", "Groceries").inOrder()
        assertThat(rows[0].fraction).isWithin(0.001f).of(0.75f)
        assertThat(rows[1].count).isEqualTo(2)
        assertThat(rows[1].previousMinor).isEqualTo(4_000)
        assertThat(rows[0].previousMinor).isEqualTo(0)
    }

    @Test fun nothingSpentGivesNoRows() {
        assertThat(SpendingViewModel.breakdown(listOf(tx(salary, 300_000, TxType.INCOME, TxSource.CHECKIN)), emptyList())).isEmpty()
    }
}
