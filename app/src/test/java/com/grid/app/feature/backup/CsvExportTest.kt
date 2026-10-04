package com.grid.app.feature.backup

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.PaymentKind
import com.grid.app.core.model.PaymentMethod
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class CsvExportTest {
    private val food = Category(1, "Restaurants", "restaurant", "orange", CategoryKind.EXPENSE, 0)
    private val salary = Category(17, "Salary", "salary", "lime", CategoryKind.INCOME, 0)
    private val card = PaymentMethod(1, "Card", PaymentKind.CARD, 0)
    private val at = LocalDateTime.of(2026, 10, 4, 12, 30).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun tx(id: Long, type: TxType, amount: Long, cat: Category, merchant: String?, note: String?) =
        Transaction(id, type, amount, "EUR", cat, card, merchant, note, at, at, TxSource.MANUAL)

    @Test fun headerAndRows() {
        val csv = CsvExport.render(
            listOf(tx(1, TxType.EXPENSE, 1250, food, "Café \"Le Bon\"", "lunch, with team"), tx(2, TxType.INCOME, 250000, salary, null, null)),
            ZoneOffset.UTC,
        )
        val lines = csv.trimEnd().lines()
        assertThat(lines[0]).isEqualTo("date,time,type,amount,currency,category,payment_method,merchant,note,source")
        assertThat(lines[1]).isEqualTo("2026-10-04,12:30,expense,-12.50,EUR,Restaurants,Card,\"Café \"\"Le Bon\"\"\",\"lunch, with team\",manual")
        assertThat(lines[2]).isEqualTo("2026-10-04,12:30,income,2500.00,EUR,Salary,Card,,,manual")
    }

    @Test fun newlinesAreQuoted() {
        val csv = CsvExport.render(listOf(tx(1, TxType.EXPENSE, 100, food, null, "line1\nline2")), ZoneOffset.UTC)
        assertThat(csv).contains("\"line1\nline2\"")
    }
}
