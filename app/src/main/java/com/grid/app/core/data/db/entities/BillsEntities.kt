package com.grid.app.core.data.db.entities

import androidx.room.ColumnInfo
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
    /** The bank payee it is paid to (MerchantKey of the bank's counterparty): payments are matched by it, not by [name]. */
    val payeeKey: String? = null,
    /** Phone, energy…: any payment to the payee counts, whatever the amount; [amountMinor] is the expected amount. */
    @ColumnInfo(defaultValue = "0") val amountVaries: Boolean = false,
    /** Found in the payment history (by AI or by pattern), not typed in by the user. */
    @ColumnInfo(defaultValue = "0") val detected: Boolean = false,
)

/** What Grid learned about a payee (from Gemini): its real name, what it is, its category, and whether it bills regularly. */
@Entity(tableName = "payee_profiles")
data class PayeeProfileEntity(
    /** MerchantKey of the bank's cleaned counterparty. */
    @PrimaryKey val key: String,
    val name: String,
    val about: String? = null,
    val categoryIconKey: String? = null,
    /** The user said it isn't a bill: never detected as one again. */
    @ColumnInfo(defaultValue = "0") val notABill: Boolean = false,
    val updatedAt: Long,
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
