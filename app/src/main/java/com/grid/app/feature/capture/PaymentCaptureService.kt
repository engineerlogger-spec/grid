package com.grid.app.feature.capture

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.grid.app.BuildConfig
import com.grid.app.core.di.AppScope
import com.grid.app.core.model.CaptureSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reads notifications from Google Wallet, PayPal and Revolut only. Everything else is ignored
 * without being read; nothing leaves the device.
 */
@AndroidEntryPoint
class PaymentCaptureService : NotificationListenerService() {

    @Inject lateinit var processor: CaptureProcessor
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        if (sbn.isOngoing || notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        var source = SOURCES[sbn.packageName]
        val extras = notification.extras
        var title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()

        // Debug builds: `adb shell cmd notification post` arrives from the shell package; a "[Revolut]"
        // style prefix on the title routes it through the real pipeline for end-to-end testing.
        if (source == null && BuildConfig.DEBUG && sbn.packageName == "com.android.shell") {
            val tag = DEBUG_TAGS.entries.firstOrNull { title.startsWith(it.key) } ?: return
            source = tag.value
            title = title.removePrefix(tag.key).trim()
        }
        val resolved = source ?: return
        scope.launch { processor.process(resolved, title, text, sbn.postTime) }
    }

    companion object {
        val SOURCES = mapOf(
            "com.google.android.apps.walletnfcrel" to CaptureSource.GOOGLE_WALLET,
            "com.paypal.android.p2pmobile" to CaptureSource.PAYPAL,
            "com.revolut.revolut" to CaptureSource.REVOLUT,
        )
        private val DEBUG_TAGS = mapOf("[Wallet]" to CaptureSource.GOOGLE_WALLET, "[PayPal]" to CaptureSource.PAYPAL, "[Revolut]" to CaptureSource.REVOLUT)

        fun isEnabled(context: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun component(context: Context) = ComponentName(context, PaymentCaptureService::class.java)

        /** Which of the supported payment apps are installed on this phone. */
        fun installedSources(context: Context): Set<CaptureSource> = SOURCES.filterKeys { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }.values.toSet()
    }
}
