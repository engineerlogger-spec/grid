package com.grid.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.grid.app.core.bank.BankSyncWorker
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.model.BankStatus
import com.grid.app.core.di.AppScope
import com.grid.app.core.notify.Channels
import com.grid.app.core.work.WorkScheduler
import com.grid.app.feature.backup.BackupWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class GridApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var bank: BankRepository
    @Inject @AppScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Channels.createAll(this)
        WorkScheduler.schedule(this)
        // Keep the automatic Drive backup schedule in sync with its settings.
        appScope.launch {
            settings.settings
                .map { it.backup.copy(account = null, lastAt = null, lastSizeBytes = null, lastError = null) }
                .distinctUntilChanged()
                .collect { BackupWorker.schedule(this@GridApp, it) }
        }
        // Background bank sync runs only while a bank connection is active.
        appScope.launch {
            bank.observeConnection()
                .map { it?.sessionId != null && it.status == BankStatus.ACTIVE }
                .distinctUntilChanged()
                .collect { active -> if (active) BankSyncWorker.schedule(this@GridApp) else BankSyncWorker.cancel(this@GridApp) }
        }
    }
}
