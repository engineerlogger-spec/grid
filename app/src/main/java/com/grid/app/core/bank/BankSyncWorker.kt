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
import androidx.work.workDataOf
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
 * A bank sync run by WorkManager: in the background every 6 hours, a few seconds after each payment notification (and
 * again while the bank doesn't list that payment yet), or for the person when they open the app or ask Ask Grid.
 */
@HiltWorker
class BankSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val live: LiveSync,
    private val bank: BankRepository,
    private val notifier: Notifier,
    private val sentLog: SentLog,
    private val clock: AppClock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mode = inputData.getString(MODE)?.let { name -> SyncMode.entries.firstOrNull { it.name == name } } ?: SyncMode.BACKGROUND
        when (val result = live.run(mode)) {
            is SyncResult.Ok -> {
                if (result.toReview > 0) {
                    notifier.post(
                        Channels.BANK, REVIEW_ID,
                        applicationContext.resources.getQuantityString(R.plurals.bank_review_notif, result.toReview, result.toReview),
                        applicationContext.getString(R.string.bank_review_notif_body),
                        LaunchTarget.DETECTED,
                    )
                }
                // A payment just notified that the bank doesn't list yet: look again a little later.
                val attempt = inputData.getInt(ATTEMPT, -1)
                if (attempt in RETRY_DELAYS_S.indices && bank.awaitingListing()) retry(applicationContext, attempt)
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
        private const val AFTER = "grid.bank.sync.after"
        private const val RETRY = "grid.bank.sync.retry"
        private const val MODE = "mode"
        private const val ATTEMPT = "attempt"
        private const val REVIEW_ID = 9101
        private const val CONSENT_ID = 9102

        /** After a payment notification: the first look (the owner's choice), then these waits while the bank doesn't list it. */
        private const val FIRST_DELAY_S = 5L
        private val RETRY_DELAYS_S = listOf(30L, 120L, 300L)

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** The safety net: a background sync every 6 hours (the payments notified are synced right away anyway). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BankSyncWorker>(6, TimeUnit.HOURS).setConstraints(network)
                .setInputData(workDataOf(MODE to SyncMode.BACKGROUND.name)).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** A sync now; [mode] PRESENT when the person asked for it (opening the app, Ask Grid). */
        fun runNow(context: Context, mode: SyncMode = SyncMode.PRESENT) {
            val request = OneTimeWorkRequestBuilder<BankSyncWorker>().setConstraints(network)
                .setInputData(workDataOf(MODE to mode.name)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }

        /** A payment notification just arrived: the 2 or 3 notifications of one payment end in one sync. */
        fun afterPayment(context: Context) {
            val request = OneTimeWorkRequestBuilder<BankSyncWorker>().setConstraints(network)
                .setInitialDelay(FIRST_DELAY_S, TimeUnit.SECONDS)
                .setInputData(workDataOf(MODE to SyncMode.BACKGROUND.name, ATTEMPT to 0)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(AFTER, ExistingWorkPolicy.REPLACE, request)
        }

        private fun retry(context: Context, attempt: Int) {
            val request = OneTimeWorkRequestBuilder<BankSyncWorker>().setConstraints(network)
                .setInitialDelay(RETRY_DELAYS_S[attempt], TimeUnit.SECONDS)
                .setInputData(workDataOf(MODE to SyncMode.BACKGROUND.name, ATTEMPT to attempt + 1)).build()
            // Appended: a retry enqueues the next one while it still runs; replacing would cancel itself.
            WorkManager.getInstance(context).enqueueUniqueWork(RETRY, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
}
