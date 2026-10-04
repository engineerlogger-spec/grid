package com.grid.app.core.bank

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.grid.app.R
import com.grid.app.core.data.prefs.SentLog
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.model.BankStatus
import com.grid.app.core.notify.Channels
import com.grid.app.core.notify.LaunchTarget
import com.grid.app.core.notify.Notifier
import com.grid.app.core.time.AppClock
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** When to remind that bank consent is ending: 7 = a week left, 1 = a day left. */
object ConsentReminders {
    private val DAY = TimeUnit.DAYS.toMillis(1)

    fun due(validUntil: Long, now: Long): Int? {
        val left = validUntil - now
        return when {
            left <= 0 -> null
            left <= DAY -> 1
            left <= 7 * DAY -> 7
            else -> null
        }
    }
}

/**
 * Background bank sync, three times a day: PSD2 allows about four unattended fetches per day,
 * which leaves one for "Sync now".
 */
@HiltWorker
class BankSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: BankSync,
    private val bank: BankRepository,
    private val notifier: Notifier,
    private val sentLog: SentLog,
    private val clock: AppClock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        when (val result = sync.run()) {
            is SyncResult.Ok -> if (result.toReview > 0) {
                notifier.post(
                    Channels.BANK, REVIEW_ID,
                    applicationContext.resources.getQuantityString(R.plurals.bank_review_notif, result.toReview, result.toReview),
                    applicationContext.getString(R.string.bank_review_notif_body),
                    LaunchTarget.DETECTED,
                )
            }
            SyncResult.Expired -> once("bank-expired-${bank.connection()?.validUntil}") {
                notifier.post(
                    Channels.BANK, CONSENT_ID,
                    applicationContext.getString(R.string.bank_expired_title), applicationContext.getString(R.string.bank_reconnect_body),
                    LaunchTarget.BANK,
                )
            }
            is SyncResult.Failed -> return if (runAttemptCount < 3) Result.retry() else Result.success()
            SyncResult.NotConnected, SyncResult.RateLimited -> Unit
        }
        remindConsent()
        return Result.success()
    }

    private suspend fun remindConsent() {
        val connection = bank.connection()?.takeIf { it.status == BankStatus.ACTIVE } ?: return
        val validUntil = connection.validUntil ?: return
        val threshold = ConsentReminders.due(validUntil, clock.millis()) ?: return
        once("bank-consent-$threshold-$validUntil") {
            notifier.post(
                Channels.BANK, CONSENT_ID,
                applicationContext.getString(if (threshold == 1) R.string.bank_consent_day else R.string.bank_consent_week),
                applicationContext.getString(R.string.bank_reconnect_body),
                LaunchTarget.BANK,
            )
        }
    }

    private suspend fun once(key: String, post: () -> Unit) {
        if (key in sentLog.sentKeys()) return
        post()
        sentLog.markSent(listOf(key), clock.today())
    }

    companion object {
        private const val PERIODIC = "grid.bank.sync"
        private const val NOW = "grid.bank.sync.now"
        private const val REVIEW_ID = 9101
        private const val CONSENT_ID = 9102

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BankSyncWorker>(8, TimeUnit.HOURS).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BankSyncWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
}
