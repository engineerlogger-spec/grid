package com.grid.app.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Per-budget-period plan: the spending goal and whether the monthly income check-in happened. */
@Entity(tableName = "period_plans")
data class PeriodPlanEntity(
    @PrimaryKey val periodStartEpochDay: Long,
    val goalMinor: Long,
    val incomeConfirmedAt: Long? = null,
)

/** Template rows that prefill the monthly income check-in ("Salary 2,500"). */
@Entity(tableName = "income_sources")
data class IncomeSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amountMinor: Long,
    val categoryId: Long,
    val position: Int,
    val active: Boolean = true,
)
