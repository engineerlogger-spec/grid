package com.grid.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.grid.app.data.model.MonthlyGoal
import com.grid.app.data.model.Subscription
import com.grid.app.data.model.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: Transaction)

    @Query("SELECT * FROM transactions ORDER BY date DESC")
    fun getAllTransactions(): Flow<List<Transaction>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: Subscription)

    @Query("SELECT * FROM subscriptions ORDER BY nextBillingDate ASC")
    fun getAllSubscriptions(): Flow<List<Subscription>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMonthlyGoal(goal: MonthlyGoal)

    @Query("SELECT * FROM monthly_goals WHERE monthYear = :monthYear LIMIT 1")
    fun getMonthlyGoal(monthYear: String): Flow<MonthlyGoal?>
}
