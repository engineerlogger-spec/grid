package com.grid.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.grid.app.core.data.db.entities.PendingPaymentEntity
import com.grid.app.core.data.db.entities.SubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY status, nextChargeEpochDay, name")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions")
    suspend fun all(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun get(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE status = 'ACTIVE' AND nextChargeEpochDay <= :epochDay")
    suspend fun dueOnOrBefore(epochDay: Long): List<SubscriptionEntity>

    @Insert
    suspend fun insert(subscription: SubscriptionEntity): Long

    @Update
    suspend fun update(subscription: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PendingDao {
    @Query("SELECT * FROM pending_payments ORDER BY status, dueEpochDay IS NULL, dueEpochDay, createdAt")
    fun observeAll(): Flow<List<PendingPaymentEntity>>

    @Query("SELECT * FROM pending_payments")
    suspend fun all(): List<PendingPaymentEntity>

    @Query("SELECT * FROM pending_payments WHERE id = :id")
    suspend fun get(id: Long): PendingPaymentEntity?

    @Insert
    suspend fun insert(pending: PendingPaymentEntity): Long

    @Update
    suspend fun update(pending: PendingPaymentEntity)

    @Query("DELETE FROM pending_payments WHERE id = :id")
    suspend fun delete(id: Long)
}
