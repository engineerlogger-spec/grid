package com.grid.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.grid.app.core.data.db.entities.IncomeSourceEntity
import com.grid.app.core.data.db.entities.PeriodPlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Query("SELECT * FROM period_plans WHERE periodStartEpochDay = :startEpochDay")
    fun observePlan(startEpochDay: Long): Flow<PeriodPlanEntity?>

    @Query("SELECT * FROM period_plans WHERE periodStartEpochDay = :startEpochDay")
    suspend fun getPlan(startEpochDay: Long): PeriodPlanEntity?


    @Query("SELECT * FROM period_plans ORDER BY periodStartEpochDay DESC")
    fun observeAllPlans(): Flow<List<PeriodPlanEntity>>

    @Upsert
    suspend fun upsertPlan(plan: PeriodPlanEntity)

    @Query("SELECT * FROM income_sources ORDER BY position")
    fun observeIncomeSources(): Flow<List<IncomeSourceEntity>>

    @Query("SELECT * FROM income_sources ORDER BY position")
    suspend fun incomeSources(): List<IncomeSourceEntity>

    @Query("DELETE FROM income_sources")
    suspend fun clearIncomeSources()

    @Insert
    suspend fun insertIncomeSources(sources: List<IncomeSourceEntity>)

    @Transaction
    suspend fun replaceIncomeSources(sources: List<IncomeSourceEntity>) {
        clearIncomeSources()
        insertIncomeSources(sources)
    }
}
