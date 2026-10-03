package com.vaulty.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.vaulty.app.data.db.TransactionDao
import com.vaulty.app.data.model.Transaction
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PaymentNotificationService : NotificationListenerService() {

    @Inject
    lateinit var transactionDao: TransactionDao

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { notification ->
            val packageName = notification.packageName
            val extras = notification.notification.extras
            val title = extras.getString("android.title") ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""

            if (isPaymentApp(packageName)) {
                Log.d("PaymentService", "Intercepted payment from \${packageName}: Title: \$title, Text: \$text")
                parseAndAddTransaction(packageName, title, text)
            }
        }
    }

    private fun isPaymentApp(packageName: String): Boolean {
        return packageName.contains("com.google.android.apps.walletnfcrel") ||
               packageName.contains("com.paypal.android.p2pmobile") ||
               packageName.contains("com.revolut.revolut")
    }

    private fun parseAndAddTransaction(packageName: String, title: String, text: String) {
        // Very basic parsing attempt. Real parsing would use Regex tailored to each app's specific notification formats
        val amountRegex = Regex("(?i)\\\\s*(?:paid|sent)\\\\s*\\\\$?(\\\\d+(?:\\\\.\\\\d+)?)\\\\b")
        val match = amountRegex.find("\$title \$text")

        val amountStr = match?.groupValues?.get(1)
        val amount = amountStr?.toDoubleOrNull()

        if (amount != null) {
            val source = when {
                packageName.contains("wallet") -> "Google Wallet"
                packageName.contains("paypal") -> "PayPal"
                packageName.contains("revolut") -> "Revolut"
                else -> "Synced Payment"
            }

            scope.launch {
                transactionDao.insertTransaction(
                    Transaction(
                        amount = amount,
                        title = title,
                        category = "Other", // Default category for synced payments
                        date = System.currentTimeMillis(),
                        isIncome = false, // Assuming notifications are for payments made
                        source = source
                    )
                )
                Log.d("PaymentService", "Successfully synced transaction of \$\$amount from \$source")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
