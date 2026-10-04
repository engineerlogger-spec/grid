package com.grid.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monthly_goals")
data class MonthlyGoal(
    @PrimaryKey val monthYear: String, // format: "MM-YYYY"
    val targetSpending: Double,
    val expectedIncome: Double
)
