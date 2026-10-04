package com.grid.app.core.model

import java.time.LocalDate

/** A billing cycle: every [count] [unit]s (e.g. every 3 months). */
data class Cycle(val unit: CycleUnit, val count: Int) {
    init {
        require(count >= 1) { "Cycle count must be at least 1" }
    }

    companion object {
        val Monthly = Cycle(CycleUnit.MONTH, 1)
    }
}

data class Subscription(
    val id: Long,
    val name: String,
    val amountMinor: Long,
    val currency: String,
    val cycle: Cycle,
    /** First charge date; later charges are computed from it. */
    val anchor: LocalDate,
    val nextCharge: LocalDate,
    val category: Category,
    val paymentMethodId: Long?,
    val remindDaysBefore: Int?,
    val autoLog: Boolean,
    val status: SubscriptionStatus,
    val colorKey: String,
    val note: String?,
)

data class SubscriptionDraft(
    val name: String,
    val amountMinor: Long,
    val currency: String,
    val cycle: Cycle,
    val nextCharge: LocalDate,
    val categoryId: Long,
    val paymentMethodId: Long? = null,
    val remindDaysBefore: Int? = 1,
    val autoLog: Boolean = true,
    val colorKey: String,
    val note: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Name is required" }
        require(amountMinor > 0) { "Amount must be positive" }
    }
}

data class PendingPayment(
    val id: Long,
    val title: String,
    val counterparty: String?,
    val direction: PendingDirection,
    val amountMinor: Long,
    val currency: String,
    val due: LocalDate?,
    val category: Category?,
    val note: String?,
    val remindDaysBefore: Int?,
    val status: PendingStatus,
    val settledAt: Long?,
    val transactionId: Long?,
)

data class PendingDraft(
    val title: String,
    val counterparty: String? = null,
    val direction: PendingDirection = PendingDirection.I_OWE,
    val amountMinor: Long,
    val currency: String,
    val due: LocalDate? = null,
    val categoryId: Long? = null,
    val note: String? = null,
    val remindDaysBefore: Int? = 1,
) {
    init {
        require(title.isNotBlank()) { "Title is required" }
        require(amountMinor > 0) { "Amount must be positive" }
    }
}
