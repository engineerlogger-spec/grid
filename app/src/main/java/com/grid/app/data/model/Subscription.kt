package com.grid.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val amount: Double,
    val billingCycle: String = "Monthly", // Monthly, Yearly
    val nextBillingDate: Long
)
