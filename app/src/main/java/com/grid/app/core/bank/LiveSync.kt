package com.grid.app.core.bank

import android.content.Context
import com.grid.app.core.ai.AiWorker
import com.grid.app.core.bills.LowFundsMonitor
import com.grid.app.core.notify.PaymentCheckNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every bank sync goes through here, whoever starts it (pull to refresh, opening the app, a payment notification, the
 * 6-hourly safety net): the sync, then what follows fresh bank data (the check after paying, Gemini's tidy-up, the
 * low-funds warning), and [syncing] for the screens while it runs.
 */
@Singleton
class LiveSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: BankSync,
    private val checks: PaymentCheckNotifier,
    private val lowFunds: LowFundsMonitor,
) {
    private val running = AtomicInteger(0)
    private val _syncing = MutableStateFlow(false)

    /** True while a sync runs: the pull-to-refresh spinner and "Syncing with Revolut…". */
    val syncing: StateFlow<Boolean> = _syncing

    suspend fun run(mode: SyncMode): SyncResult {
        _syncing.value = running.incrementAndGet() > 0
        val result = try {
            sync.run(mode)
        } finally {
            _syncing.value = running.decrementAndGet() > 0
        }
        if (result is SyncResult.Ok) {
            checks.notifyFresh(result.newRowIds)
            // New payees and bills for Gemini to look at (does nothing without a key).
            AiWorker.runNow(context)
            // A fresh balance: will it cover the bills still ahead this month?
            lowFunds.notifyIfShort()
        }
        return result
    }
}
