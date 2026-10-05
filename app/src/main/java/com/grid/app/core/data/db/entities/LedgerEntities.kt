package com.grid.app.core.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.PaymentKind
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType

@Entity(tableName = "categories", indices = [Index("kind", "position")])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Key into the app's icon set (see CategoryIcons). Stable, never shown to users. */
    val iconKey: String,
    /** Key into the category hue palette (see GridColors.category). */
    val colorKey: String,
    val kind: CategoryKind,
    val position: Int,
    val archived: Boolean = false,
    /** Optional monthly budget for this category, in the app currency's minor units. */
    val monthlyLimitMinor: Long? = null,
)

@Entity(tableName = "payment_methods")
data class PaymentMethodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: PaymentKind,
    val position: Int,
    val archived: Boolean = false,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = PaymentMethodEntity::class, parentColumns = ["id"], childColumns = ["paymentMethodId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index("occurredAt"), Index("categoryId"), Index("paymentMethodId"),
        Index("subscriptionId"), Index("pendingId"), Index("captureId"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TxType,
    val amountMinor: Long,
    val currency: String,
    val categoryId: Long,
    val paymentMethodId: Long? = null,
    val merchant: String? = null,
    val note: String? = null,
    /** When the money moved (epoch millis). */
    val occurredAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val source: TxSource = TxSource.MANUAL,
    val subscriptionId: Long? = null,
    val pendingId: Long? = null,
    val captureId: Long? = null,
    /** Booked from the bank without a known category: shown in the review list until the user picks one. */
    @ColumnInfo(defaultValue = "0") val needsReview: Boolean = false,
)

/**
 * A ledger entry the user deleted, kept for a while so it can be restored exactly (same id, same bank link).
 * Ids are never reused (AUTOINCREMENT), so putting it back under [id] is safe.
 */
@Entity(tableName = "deleted_transactions", indices = [Index("deletedAt")])
data class DeletedTransactionEntity(
    @PrimaryKey val id: Long,
    val type: TxType,
    val amountMinor: Long,
    val currency: String,
    val categoryId: Long,
    val paymentMethodId: Long?,
    val merchant: String?,
    val note: String?,
    val occurredAt: Long,
    val createdAt: Long,
    val source: TxSource,
    val subscriptionId: Long?,
    val pendingId: Long?,
    val captureId: Long?,
    val needsReview: Boolean,
    /** The bank row it was booked from: linked again on restore (and kept from being re-booked meanwhile). */
    val bankRowId: Long?,
    val deletedAt: Long,
)
