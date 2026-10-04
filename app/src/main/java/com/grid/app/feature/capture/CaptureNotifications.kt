package com.grid.app.feature.capture

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.grid.app.R
import com.grid.app.core.data.repo.CaptureItem
import com.grid.app.core.data.repo.CaptureRepository
import com.grid.app.core.di.AppScope
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.CaptureSource
import com.grid.app.core.model.Category
import com.grid.app.core.money.MoneyFormatter
import com.grid.app.core.notify.Channels
import com.grid.app.core.notify.LaunchTarget
import com.grid.app.core.notify.Notifier
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Posts "payment detected" / "added automatically" notifications with one-tap actions. */
@Singleton
class NotificationCaptureAlerts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: Notifier,
    private val formatter: MoneyFormatter,
) : CaptureAlerts {

    override fun added(capture: CaptureItem, categoryName: String) {
        val amount = capture.amountMinor?.let { formatter.format(it, capture.currency ?: return) } ?: return
        notifier.post(
            Channels.DETECTED, notificationId(capture.id),
            context.getString(R.string.capture_added_title, amount, capture.merchant ?: sourceName(capture.source)),
            context.getString(R.string.capture_added_body, categoryName, sourceName(capture.source)),
            LaunchTarget.DETECTED,
        ) { it.addAction(0, context.getString(R.string.action_undo), action(CaptureActionReceiver.UNDO, capture.id)) }
    }

    override fun detected(capture: CaptureItem, suggestions: List<Category>) {
        val amount = capture.amountMinor?.let { formatter.format(it, capture.currency ?: return) } ?: return
        val who = capture.merchant
        val title = when {
            who == null -> amount
            capture.direction == CaptureDirection.IN -> context.getString(R.string.capture_from, amount, who)
            else -> context.getString(R.string.capture_at, amount, who)
        }
        notifier.post(
            Channels.DETECTED, notificationId(capture.id), title,
            context.getString(R.string.capture_detected_body, sourceName(capture.source)),
            LaunchTarget.DETECTED,
        ) { builder ->
            suggestions.forEach { c -> builder.addAction(0, c.name, action(CaptureActionReceiver.ACCEPT, capture.id, c.id)) }
            builder.addAction(0, context.getString(R.string.capture_review), notifier.launchIntent(LaunchTarget.DETECTED, notificationId(capture.id)))
        }
    }

    private fun action(kind: String, captureId: Long, categoryId: Long = 0): PendingIntent = PendingIntent.getBroadcast(
        context, (kind.hashCode() * 31 + captureId.toInt()) * 31 + categoryId.toInt(),
        Intent(context, CaptureActionReceiver::class.java).setAction(kind)
            .putExtra(CaptureActionReceiver.EXTRA_CAPTURE, captureId)
            .putExtra(CaptureActionReceiver.EXTRA_CATEGORY, categoryId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun sourceName(source: CaptureSource) = context.getString(
        when (source) {
            CaptureSource.GOOGLE_WALLET -> R.string.source_wallet
            CaptureSource.PAYPAL -> R.string.source_paypal
            CaptureSource.REVOLUT -> R.string.source_revolut
        },
    )

    companion object {
        fun notificationId(captureId: Long) = 80_000 + captureId.toInt()
    }
}

/** Handles notification actions: categorise, undo an auto-add. */
@AndroidEntryPoint
class CaptureActionReceiver : BroadcastReceiver() {

    @Inject lateinit var captures: CaptureRepository
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val captureId = intent.getLongExtra(EXTRA_CAPTURE, -1).takeIf { it > 0 } ?: return
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACCEPT -> captures.accept(captureId, intent.getLongExtra(EXTRA_CATEGORY, -1))
                    UNDO -> captures.undo(captureId)
                }
                NotificationManagerCompat.from(context).cancel(NotificationCaptureAlerts.notificationId(captureId))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACCEPT = "com.grid.app.capture.ACCEPT"
        const val UNDO = "com.grid.app.capture.UNDO"
        const val EXTRA_CAPTURE = "capture"
        const val EXTRA_CATEGORY = "category"
    }
}

