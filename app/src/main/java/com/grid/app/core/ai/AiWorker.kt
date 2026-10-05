package com.grid.app.core.ai

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Gemini's pass over new payees and bills, after a bank sync (or when the user asks). Quiet when there's no key. */
@HiltWorker
class AiWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val assistant: AiAssistant,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (val result = assistant.run()) {
        is AiRunResult.Failed -> if (result.error is AiError.Network && runAttemptCount < 3) Result.retry() else Result.success()
        else -> Result.success()
    }

    companion object {
        private const val NAME = "grid.ai.run"

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AiWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // A run already queued or going covers this request too.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
