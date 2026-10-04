package com.grid.app.core.bank

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Reads one Enable Banking transaction object; also used to re-read stored raw rows when the rules improve. */
object RemoteTxJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): RemoteTx? = runCatching { from(json.parseToJsonElement(raw).jsonObject) }.getOrNull()

    fun from(o: JsonObject): RemoteTx {
        val amount = o.obj("transaction_amount")
        val code = o.obj("bank_transaction_code")
        return RemoteTx(
            transactionId = o.str("transaction_id"),
            entryReference = o.str("entry_reference"),
            amount = amount?.str("amount") ?: "0",
            currency = amount?.str("currency") ?: "EUR",
            creditDebit = o.str("credit_debit_indicator"),
            status = o.str("status"),
            bookingDate = o.str("booking_date"),
            valueDate = o.str("value_date"),
            transactionDate = o.str("transaction_date"),
            creditorName = o.obj("creditor")?.str("name"),
            creditorIban = o.obj("creditor_account")?.str("iban"),
            debtorName = o.obj("debtor")?.str("name"),
            debtorIban = o.obj("debtor_account")?.str("iban"),
            remittance = (o["remittance_information"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            mcc = o.str("merchant_category_code"),
            bankTxCode = listOfNotNull(code?.str("code"), code?.str("sub_code"), code?.str("description")).joinToString(" ").ifBlank { null },
            rawJson = o.toString(),
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}
