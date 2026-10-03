package com.vaulty.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.vaulty.app.data.model.MonthlyGoal
import com.vaulty.app.data.model.Subscription
import com.vaulty.app.data.model.Transaction

@Database(
    entities = [Transaction::class, Subscription::class, MonthlyGoal::class],
    version = 2,
    exportSchema = false
)
abstract class VaultyDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
}
