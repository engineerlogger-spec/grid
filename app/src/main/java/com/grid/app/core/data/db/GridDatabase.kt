package com.grid.app.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.grid.app.core.data.db.dao.CaptureDao
import com.grid.app.core.data.db.dao.CategoryDao
import com.grid.app.core.data.db.dao.MerchantRuleDao
import com.grid.app.core.data.db.dao.PaymentMethodDao
import com.grid.app.core.data.db.dao.PendingDao
import com.grid.app.core.data.db.dao.PlanDao
import com.grid.app.core.data.db.dao.SubscriptionDao
import com.grid.app.core.data.db.dao.TransactionDao
import com.grid.app.core.data.db.entities.CaptureEntity
import com.grid.app.core.data.db.entities.CategoryEntity
import com.grid.app.core.data.db.entities.IncomeSourceEntity
import com.grid.app.core.data.db.entities.MerchantRuleEntity
import com.grid.app.core.data.db.entities.PaymentMethodEntity
import com.grid.app.core.data.db.entities.PendingPaymentEntity
import com.grid.app.core.data.db.entities.PeriodPlanEntity
import com.grid.app.core.data.db.entities.SubscriptionEntity
import com.grid.app.core.data.db.entities.TransactionEntity

/**
 * Schema v1. Every schema change must bump the version and ship a tested Migration —
 * never fall back to destructive migration: this is people's financial history.
 */
@Database(
    entities = [
        CategoryEntity::class, PaymentMethodEntity::class, TransactionEntity::class,
        PeriodPlanEntity::class, IncomeSourceEntity::class,
        SubscriptionEntity::class, PendingPaymentEntity::class,
        CaptureEntity::class, MerchantRuleEntity::class,
    ],
    version = GridDatabase.VERSION,
    exportSchema = true,
)
abstract class GridDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun paymentMethodDao(): PaymentMethodDao
    abstract fun transactionDao(): TransactionDao
    abstract fun planDao(): PlanDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun pendingDao(): PendingDao
    abstract fun captureDao(): CaptureDao

    companion object {
        const val NAME = "grid.db"
        /** Schema version. Bump together with a Migration and an exported schema; backups record it. */
        const val VERSION = 1

        fun build(context: Context): GridDatabase =
            Room.databaseBuilder(context, GridDatabase::class.java, NAME).addCallback(SeedCallback).build()

        fun inMemory(context: Context): GridDatabase =
            Room.inMemoryDatabaseBuilder(context, GridDatabase::class.java).addCallback(SeedCallback).build()
    }
}

internal object SeedCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        Seed.categories.groupBy { it.kind }.forEach { (_, list) ->
            list.forEachIndexed { position, c ->
                db.execSQL(
                    "INSERT INTO categories (name, iconKey, colorKey, kind, position, archived) VALUES (?, ?, ?, ?, ?, 0)",
                    arrayOf<Any>(c.name, c.iconKey, c.colorKey, c.kind.name, position),
                )
            }
        }
        Seed.paymentMethods.forEachIndexed { position, m ->
            db.execSQL(
                "INSERT INTO payment_methods (name, kind, position, archived) VALUES (?, ?, ?, 0)",
                arrayOf<Any>(m.name, m.kind.name, position),
            )
        }
    }
}
