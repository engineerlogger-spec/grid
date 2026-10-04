package com.grid.app.feature.backup

import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TxType
import com.grid.app.core.money.Currencies
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Spreadsheet-friendly export of every transaction (RFC 4180, signed decimal amounts). */
object CsvExport {
    private const val HEADER = "date,time,type,amount,currency,category,payment_method,merchant,note,source"
    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun render(transactions: List<Transaction>, zone: ZoneId): String = buildString {
        append(HEADER).append('\n')
        transactions.sortedBy { it.occurredAt }.forEach { tx ->
            val at = Instant.ofEpochMilli(tx.occurredAt).atZone(zone)
            val amount = Currencies.toDecimal(tx.signedMinor, tx.currency).toPlainString()
            listOf(
                at.format(dateFormat),
                at.format(timeFormat),
                if (tx.type == TxType.EXPENSE) "expense" else "income",
                amount,
                tx.currency,
                tx.category.name,
                tx.method?.name.orEmpty(),
                tx.merchant.orEmpty(),
                tx.note.orEmpty(),
                tx.source.name.lowercase(Locale.ROOT),
            ).joinTo(this, ",") { escape(it) }
            append('\n')
        }
    }

    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field
}
