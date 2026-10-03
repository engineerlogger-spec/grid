package com.vaulty.app.di

import android.content.Context
import androidx.room.Room
import com.vaulty.app.data.db.TransactionDao
import com.vaulty.app.data.db.VaultyDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VaultyDatabase {
        return Room.databaseBuilder(
            context,
            VaultyDatabase::class.java,
            "vaulty_database"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideTransactionDao(database: VaultyDatabase): TransactionDao {
        return database.transactionDao()
    }
}
