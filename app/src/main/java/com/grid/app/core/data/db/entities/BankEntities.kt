package com.grid.app.core.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.grid.app.core.model.BankStatus
import com.grid.app.core.model.BankTxKind
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.PaymentKind

/** The Open Banking link to one bank (Revolut via Enable Banking). Secrets live in BankKeyStore, never here. */
@Entity(tableName = "bank_connections")
data class BankConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val aspspName: String,
    val aspspCountry: String,
    val sessionId: String? = null,
    /** When the bank consent ends (epoch millis); the user must approve again after it. */
    val validUntil: Long? = null,
    val status: BankStatus,
    /** The `state` sent with an authorisation in progress, checked when the bank redirects back. */
    val authState: String? = null,
    val backfillFromEpochDay: Long? = null,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "bank_accounts",
    foreignKeys = [ForeignKey(entity = BankConnectionEntity::class, parentColumns = ["id"], childColumns = ["connectionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["connectionId", "identificationHash"], unique = true)],
)
data class BankAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val connectionId: Long,
    /** Provider account id; changes with every new consent. */
    val uid: String,
    /** Stable across consents: re-links the same account after a reconnect. */
    val identificationHash: String,
    val name: String? = null,
    val currency: String,
    val iban: String? = null,
    val enabled: Boolean,
    val syncedThroughEpochDay: Long? = null,
    /** Available balance at the last sync (minor units, account currency): for the low-funds warning. */
    val balanceMinor: Long? = null,
    val balanceAt: Long? = null,
)

/** A booked bank transaction as fetched, and what Grid did with it. */
@Entity(
    tableName = "bank_transactions",
    foreignKeys = [
        ForeignKey(entity = BankAccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["accountId", "externalId"], unique = true), Index("state"), Index("transactionId")],
)
data class BankTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val externalId: String,
    val bookingEpochDay: Long,
    val occurredAt: Long,
    val amountMinor: Long,
    val currency: String,
    val direction: CaptureDirection,
    val kind: BankTxKind,
    val counterparty: String? = null,
    /** MerchantKey of the cleaned counterparty: groups review items and keys learned rules. */
    val counterpartyKey: String? = null,
    val counterpartyIban: String? = null,
    val description: String? = null,
    val mcc: String? = null,
    /** How it was really paid when the descriptor tells (e.g. PayPal). */
    val via: PaymentKind? = null,
    val state: BankTxState,
    val transactionId: Long? = null,
    val rawJson: String,
    val createdAt: Long,
    /** For own transfers: a date in the budget period it counts in, when the user moved it (null = its own date's period). */
    val countInEpochDay: Long? = null,
)

/** Counterparties that are the user's own accounts (e.g. the bank the salary is paid into). Table name kept from v2. */
@Entity(tableName = "bank_ignore_rules")
data class OwnAccountRuleEntity(
    @PrimaryKey val counterpartyKey: String,
    val createdAt: Long,
)
