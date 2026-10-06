package com.grid.app.feature.common

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.grid.app.R
import com.grid.app.core.bank.LiveSync
import com.grid.app.core.bank.SyncMode
import com.grid.app.core.bank.SyncResult
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.time.AppClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Pull to refresh and "Updated 2 min ago", shared by Home and Activity. */
@HiltViewModel
class RefreshViewModel @Inject constructor(
    private val live: LiveSync,
    bank: BankRepository,
    private val clock: AppClock,
) : ViewModel() {
    val syncing: StateFlow<Boolean> = live.syncing

    /** When the bank was last read; null without a bank connection. */
    val lastSyncAt: StateFlow<Long?> = bank.observeConnection()
        .map { c -> c?.takeIf { it.sessionId != null }?.lastSyncAt }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _results = MutableSharedFlow<SyncResult>(extraBufferCapacity = 1)
    val results: SharedFlow<SyncResult> = _results

    /** The person pulled down: the bank is read as them, so its daily background limit doesn't apply. */
    fun refresh() = viewModelScope.launch { _results.emit(live.run(SyncMode.PRESENT)) }

    fun now(): Long = clock.millis()
}

/** Swipe down to read the bank now; says what came of it. Also spins while any sync runs (after a payment, say). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankRefreshBox(modifier: Modifier = Modifier, vm: RefreshViewModel = hiltViewModel(), content: @Composable BoxScope.() -> Unit) {
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    val res = LocalContext.current.resources
    LaunchedEffect(vm) {
        vm.results.collect { result ->
            when (result) {
                is SyncResult.Ok -> {
                    val n = result.booked + result.toReview
                    messenger.show(if (n == 0) res.getString(R.string.bank_synced_none) else res.getQuantityString(R.plurals.bank_synced, n, n))
                }
                SyncResult.RateLimited -> messenger.show(res.getString(R.string.bank_rate_limited))
                SyncResult.Expired -> messenger.show(res.getString(R.string.bank_expired_snack))
                is SyncResult.Failed -> messenger.show(res.getString(R.string.bank_failed, result.message))
                SyncResult.NotConnected -> Unit
            }
        }
    }
    PullToRefreshBox(isRefreshing = syncing, onRefresh = { vm.refresh() }, modifier = modifier, content = content)
}

/** "Updated 2 min ago", or "Syncing with Revolut…" while the bank is read. Nothing without a bank. */
@Composable
fun BankUpdatedLine(modifier: Modifier = Modifier, vm: RefreshViewModel = hiltViewModel()) {
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val last by vm.lastSyncAt.collectAsStateWithLifecycle()
    val at = last ?: return
    var now by remember { mutableLongStateOf(vm.now()) }
    LaunchedEffect(at) {
        while (true) {
            now = vm.now()
            delay(30_000)
        }
    }
    val minutes = ((now - at) / 60_000).coerceAtLeast(0).toInt()
    val text = when {
        syncing -> stringResource(R.string.refresh_syncing)
        minutes < 1 -> stringResource(R.string.refresh_updated_now)
        minutes < 60 -> pluralStringResource(R.plurals.refresh_updated_minutes, minutes, minutes)
        minutes < 24 * 60 -> pluralStringResource(R.plurals.refresh_updated_hours, minutes / 60, minutes / 60)
        else -> stringResource(R.string.refresh_updated_on, shortDate(at.toLocalDate()))
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = GridTheme.colors.muted, modifier = modifier)
}
