package com.grid.app.feature.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.grid.app.R
import com.grid.app.core.data.prefs.BackupFrequency
import com.grid.app.core.data.prefs.BackupSettings
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.notify.Channels
import com.grid.app.core.notify.LaunchTarget
import com.grid.app.core.notify.Notifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Automatic Drive backup, like WhatsApp's nightly one. Never prompts: asks to reconnect via a notification. */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val auth: DriveAuth,
    private val drive: DriveBackup,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val backup = settings.settings.first().backup
        if (!backup.connected || backup.frequency == BackupFrequency.OFF) return Result.success()
        return when (val result = auth.authorize()) {
            is DriveAuthResult.Token -> try {
                drive.backupNow(result.accessToken)
                Result.success()
            } catch (e: DriveError.Network) {
                Result.retry()
            } catch (e: Exception) {
                settings.recordBackupError(e.message ?: "Backup failed")
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
            is DriveAuthResult.NeedsConsent -> {
                settings.recordBackupError(applicationContext.getString(R.string.backup_reconnect))
                notifier.post(
                    Channels.BACKUP, RECONNECT_ID,
                    applicationContext.getString(R.string.backup_reconnect),
                    applicationContext.getString(R.string.backup_reconnect_body),
                    LaunchTarget.BACKUP,
                )
                Result.success()
            }
            DriveAuthResult.NotConfigured -> Result.success()
            is DriveAuthResult.Failed -> Result.retry()
        }
    }

    companion object {
        private const val NAME = "grid.backup"
        private const val RECONNECT_ID = 9001

        /** (Re)applies the schedule for the current settings: frequency and network constraint. */
        fun schedule(context: Context, backup: BackupSettings) {
            val wm = WorkManager.getInstance(context)
            val days = when (backup.frequency) {
                BackupFrequency.OFF -> null
                BackupFrequency.DAILY -> 1L
                BackupFrequency.WEEKLY -> 7L
                BackupFrequency.MONTHLY -> 30L
            }
            if (!backup.connected || days == null) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (backup.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<BackupWorker>(days, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setInitialDelay(days, TimeUnit.DAYS)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
