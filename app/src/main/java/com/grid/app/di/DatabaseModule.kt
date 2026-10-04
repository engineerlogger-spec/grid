package com.grid.app.di

import android.content.Context
import androidx.room.Room
import com.grid.app.data.db.TransactionDao
import com.grid.app.data.db.GridDatabase
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
    fun provideDatabase(@ApplicationContext context: Context): GridDatabase {
        return Room.databaseBuilder(
            context,
            GridDatabase::class.java,
            "grid_database"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideTransactionDao(database: GridDatabase): TransactionDao {
        return database.transactionDao()
    }
}
