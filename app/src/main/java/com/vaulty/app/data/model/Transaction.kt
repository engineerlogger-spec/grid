package com.vaulty.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val amount: Double,
    val title: String,
    val category: String,
    val date: Long,
    val isIncome: Boolean,
    val isPending: Boolean = false,
    val source: String = "Manual" // Manual, Wallet, PayPal, Revolut
)
