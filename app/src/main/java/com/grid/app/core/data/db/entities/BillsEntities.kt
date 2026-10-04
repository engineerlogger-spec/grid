package com.grid.app.core.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.grid.app.core.model.CycleUnit
import com.grid.app.core.model.PendingDirection
import com.grid.app.core.model.PendingStatus
import com.grid.app.core.model.SubscriptionStatus

@Entity(tableName = "subscriptions", indices = [Index("status", "nextChargeEpochDay")])
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amountMinor: Long,
    val currency: String,
    val cycleUnit: CycleUnit,
    val cycleCount: Int,
    /** First billing date; later dates are computed from it so month-end anchors survive short months. */
    val anchorEpochDay: Long,
    val nextChargeEpochDay: Long,
    val categoryId: Long,
    val paymentMethodId: Long? = null,
    val remindDaysBefore: Int? = 1,
    val autoLog: Boolean = true,
    val status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    val colorKey: String,
    val note: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "pending_payments", indices = [Index("status", "dueEpochDay")])
data class PendingPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val counterparty: String? = null,
    val direction: PendingDirection,
    val amountMinor: Long,
    val currency: String,
    val dueEpochDay: Long? = null,
    val categoryId: Long? = null,
    val note: String? = null,
    val remindDaysBefore: Int? = 1,
    val status: PendingStatus = PendingStatus.PENDING,
    val settledAt: Long? = null,
    val transactionId: Long? = null,
    val createdAt: Long,
)
