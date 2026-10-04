package com.grid.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.grid.app.data.model.MonthlyGoal
import com.grid.app.data.model.Subscription
import com.grid.app.data.model.Transaction

@Database(
    entities = [Transaction::class, Subscription::class, MonthlyGoal::class],
    version = 2,
    exportSchema = false
)
abstract class GridDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
}
