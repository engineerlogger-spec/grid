package com.grid.app.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.grid.app.MainActivity
import com.grid.app.R
import com.grid.app.core.bills.Reminder
import com.grid.app.core.bills.ReminderKind
import com.grid.app.core.model.PendingDirection
import com.grid.app.core.money.MoneyFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

object Channels {
    const val BILLS = "bills"
    const val BUDGET = "budget"
    const val DETECTED = "detected"
    const val BACKUP = "backup"
    const val CHECKIN = "checkin"

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf(
            Triple(BILLS, R.string.channel_bills, R.string.channel_bills_desc) to NotificationManager.IMPORTANCE_DEFAULT,
            Triple(BUDGET, R.string.channel_budget, R.string.channel_budget_desc) to NotificationManager.IMPORTANCE_DEFAULT,
            Triple(DETECTED, R.string.channel_detected, R.string.channel_detected_desc) to NotificationManager.IMPORTANCE_HIGH,
            Triple(BACKUP, R.string.channel_backup, R.string.channel_backup_desc) to NotificationManager.IMPORTANCE_LOW,
            Triple(CHECKIN, R.string.channel_checkin, R.string.channel_checkin_desc) to NotificationManager.IMPORTANCE_DEFAULT,
        ).forEach { (spec, importance) ->
            val (id, name, desc) = spec
            manager.createNotificationChannel(NotificationChannel(id, context.getString(name), importance).apply { description = context.getString(desc) })
        }
    }
}

/** Where a notification tap should land inside the app. */
enum class LaunchTarget { BILLS, INSIGHTS, CHECK_IN, ADD_EXPENSE, ADD_INCOME, DETECTED, BACKUP;
    companion object {
        const val EXTRA = "grid.launch"
        fun from(intent: Intent?): LaunchTarget? = intent?.getStringExtra(EXTRA)?.let { name -> entries.firstOrNull { it.name == name } }
    }
}

@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val formatter: MoneyFormatter,
) {
    val canPost: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun reminder(reminder: Reminder) {
        val amount = formatter.format(reminder.amountMinor, reminder.currency)
        val days = reminder.daysUntil
        val title = when (reminder.kind) {
            ReminderKind.SUBSCRIPTION_RENEWS -> when (days) {
                0 -> context.getString(R.string.notif_sub_today, reminder.title, amount)
                1 -> context.getString(R.string.notif_sub_tomorrow, reminder.title, amount)
                else -> context.getString(R.string.notif_sub_in_days, reminder.title, amount, days)
            }
            ReminderKind.PENDING_DUE -> when {
                reminder.direction == PendingDirection.OWED_TO_ME -> context.getString(R.string.notif_collect_due, reminder.title, amount)
                days == 0 -> context.getString(R.string.notif_due_today, reminder.title, amount)
                days == 1 -> context.getString(R.string.notif_due_tomorrow, reminder.title, amount)
                else -> context.getString(R.string.notif_due_in_days, reminder.title, amount, days)
            }
            ReminderKind.PENDING_OVERDUE -> context.getString(R.string.notif_overdue, reminder.title, amount)
        }
        post(Channels.BILLS, reminder.key.hashCode(), title, context.getString(R.string.notif_bills_body), LaunchTarget.BILLS)
    }

    fun checkIn() = post(
        Channels.CHECKIN, CHECKIN_ID,
        context.getString(R.string.notif_checkin_title), context.getString(R.string.notif_checkin_body), LaunchTarget.CHECK_IN,
    )

    fun post(channel: String, id: Int, title: String, body: String, target: LaunchTarget, builder: (NotificationCompat.Builder) -> Unit = {}) {
        if (!canPost) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF4E7D00.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(launchIntent(target, id))
            .also(builder)
            .build()
        @Suppress("MissingPermission") // checked by canPost
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun launchIntent(target: LaunchTarget, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(LaunchTarget.EXTRA, target.name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHECKIN_ID = 7001
    }
}
