package com.grid.app.core.model

import java.time.LocalDate

data class Category(
    val id: Long,
    val name: String,
    val iconKey: String,
    val colorKey: String,
    val kind: CategoryKind,
    val position: Int,
    val archived: Boolean = false,
    val monthlyLimitMinor: Long? = null,
)

data class PaymentMethod(
    val id: Long,
    val name: String,
    val kind: PaymentKind,
    val position: Int,
    val archived: Boolean = false,
)

data class Transaction(
    val id: Long,
    val type: TxType,
    val amountMinor: Long,
    val currency: String,
    val category: Category,
    val method: PaymentMethod?,
    val merchant: String?,
    val note: String?,
    /** Epoch millis. */
    val occurredAt: Long,
    val createdAt: Long,
    val source: TxSource,
    val subscriptionId: Long? = null,
    val pendingId: Long? = null,
    val captureId: Long? = null,
    val needsReview: Boolean = false,
    /** Display-only row for money moved between the user's own accounts: in Activity, in = income and out = spent. */
    val ownTransfer: Boolean = false,
) {
    /** What to call this transaction in lists: merchant, else note, else the category name. */
    val title: String
        get() = merchant?.takeIf { it.isNotBlank() } ?: note?.takeIf { it.isNotBlank() } ?: category.name

    /** Amount with sign: expenses negative, income positive. */
    val signedMinor: Long get() = if (type == TxType.EXPENSE) -amountMinor else amountMinor

    fun toDraft(): TransactionDraft = TransactionDraft(
        type = type, amountMinor = amountMinor, currency = currency, categoryId = category.id,
        paymentMethodId = method?.id, merchant = merchant, note = note, occurredAt = occurredAt,
        source = source, subscriptionId = subscriptionId, pendingId = pendingId, captureId = captureId, needsReview = needsReview,
    )
}

/** An entry in "Recently deleted". */
data class DeletedEntry(val tx: Transaction, val deletedAt: Long)

/** Everything needed to create or update a transaction. */
data class TransactionDraft(
    val type: TxType,
    val amountMinor: Long,
    val currency: String,
    val categoryId: Long,
    val paymentMethodId: Long? = null,
    val merchant: String? = null,
    val note: String? = null,
    val occurredAt: Long,
    val source: TxSource = TxSource.MANUAL,
    val subscriptionId: Long? = null,
    val pendingId: Long? = null,
    val captureId: Long? = null,
    val needsReview: Boolean = false,
) {
    init {
        require(amountMinor > 0) { "Amount must be positive (type carries the direction)" }
    }
}

/** A one-tap quick-add suggestion learned from repeated entries ("Coffee €3.50"). */
data class QuickSuggestion(val label: String, val amountMinor: Long, val category: Category, val paymentMethodId: Long?)

data class IncomeSource(val id: Long, val name: String, val amountMinor: Long, val categoryId: Long, val position: Int, val active: Boolean)

/** One income row confirmed in the monthly check-in. */
data class IncomeLine(val name: String, val amountMinor: Long, val categoryId: Long, val active: Boolean = true)

data class PeriodPlan(val periodStart: LocalDate, val goalMinor: Long, val incomeConfirmedAt: Long?)
