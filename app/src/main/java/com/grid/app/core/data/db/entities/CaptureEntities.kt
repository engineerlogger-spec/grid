package com.grid.app.core.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.CaptureSource
import com.grid.app.core.model.CaptureStatus

/** A payment detected from a Wallet / PayPal / Revolut notification. */
@Entity(tableName = "captures", indices = [Index(value = ["dedupeKey"], unique = true), Index("status", "postedAt")])
data class CaptureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: CaptureSource,
    val postedAt: Long,
    val title: String,
    val text: String,
    val amountMinor: Long? = null,
    val currency: String? = null,
    val merchant: String? = null,
    val direction: CaptureDirection = CaptureDirection.OUT,
    val status: CaptureStatus,
    val transactionId: Long? = null,
    val dedupeKey: String,
)

/** Learned "this merchant → this category" mapping, keyed by a normalized merchant name. */
@Entity(tableName = "merchant_rules")
data class MerchantRuleEntity(
    @PrimaryKey val merchantKey: String,
    val categoryId: Long,
    val paymentMethodId: Long? = null,
    val hits: Int = 1,
    val updatedAt: Long,
    /** The user chose this category (an edit, a quick add, a detected payment): automatic sorting never overrides it. */
    @androidx.room.ColumnInfo(defaultValue = "0") val userSet: Boolean = false,
)
