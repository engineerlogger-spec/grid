package com.grid.app.core.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.prefs.SentLog
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BudgetAlertListener
import com.grid.app.core.data.repo.LedgerListener
import dagger.Binds
import dagger.multibindings.IntoSet
import com.grid.app.core.money.MoneyFormatter
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.SystemAppClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.Locale
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-lifetime scope for work that must outlive a screen (e.g. finishing a save after navigation). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): GridDatabase = GridDatabase.build(context)

    @Provides @Singleton
    fun settings(@ApplicationContext context: Context): SettingsRepository = SettingsRepository(
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") },
    )

    @Provides @Singleton
    fun sentLog(@ApplicationContext context: Context): SentLog = SentLog(
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("sent_log") },
    )

    @Provides
    fun clock(): AppClock = SystemAppClock

    @Provides
    fun moneyFormatter(): MoneyFormatter = MoneyFormatter(Locale.getDefault())

    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LedgerListenerModule {
    /** Declares the set so repositories can inject it even if no listener is bound. */
    @Multibinds
    abstract fun ledgerListeners(): Set<LedgerListener>

    @Binds @IntoSet
    abstract fun budgetAlerts(listener: BudgetAlertListener): LedgerListener
}
