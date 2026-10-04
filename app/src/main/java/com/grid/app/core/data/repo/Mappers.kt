package com.grid.app.core.data.repo

import com.grid.app.core.data.db.entities.CategoryEntity
import com.grid.app.core.data.db.entities.IncomeSourceEntity
import com.grid.app.core.data.db.entities.PaymentMethodEntity
import com.grid.app.core.data.db.entities.PeriodPlanEntity
import com.grid.app.core.data.db.entities.TransactionEntity
import com.grid.app.core.model.Category
import com.grid.app.core.model.IncomeSource
import com.grid.app.core.model.PaymentMethod
import com.grid.app.core.model.PeriodPlan
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TransactionDraft
import java.time.LocalDate

internal fun CategoryEntity.toDomain() = Category(id, name, iconKey, colorKey, kind, position, archived, monthlyLimitMinor)

internal fun Category.toEntity() = CategoryEntity(id, name, iconKey, colorKey, kind, position, archived, monthlyLimitMinor)

internal fun PaymentMethodEntity.toDomain() = PaymentMethod(id, name, kind, position, archived)

internal fun IncomeSourceEntity.toDomain() = IncomeSource(id, name, amountMinor, categoryId, position, active)

internal fun PeriodPlanEntity.toDomain() = PeriodPlan(LocalDate.ofEpochDay(periodStartEpochDay), goalMinor, incomeConfirmedAt)

/** Null when the category is missing (cannot happen with the RESTRICT foreign key, but never crash a list). */
internal fun TransactionEntity.toDomain(categories: Map<Long, Category>, methods: Map<Long, PaymentMethod>): Transaction? {
    val category = categories[categoryId] ?: return null
    return Transaction(
        id = id, type = type, amountMinor = amountMinor, currency = currency, category = category,
        method = paymentMethodId?.let { methods[it] }, merchant = merchant, note = note,
        occurredAt = occurredAt, createdAt = createdAt, source = source,
        subscriptionId = subscriptionId, pendingId = pendingId, captureId = captureId, needsReview = needsReview,
    )
}

internal fun TransactionDraft.toEntity(id: Long = 0, createdAt: Long, updatedAt: Long) = TransactionEntity(
    id = id, type = type, amountMinor = amountMinor, currency = currency, categoryId = categoryId,
    paymentMethodId = paymentMethodId, merchant = merchant?.trim()?.ifBlank { null }, note = note?.trim()?.ifBlank { null },
    occurredAt = occurredAt, createdAt = createdAt, updatedAt = updatedAt, source = source,
    subscriptionId = subscriptionId, pendingId = pendingId, captureId = captureId, needsReview = needsReview,
)
