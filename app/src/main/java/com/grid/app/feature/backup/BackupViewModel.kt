package com.grid.app.feature.backup

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.R
import com.grid.app.core.data.prefs.BackupFrequency
import com.grid.app.core.data.prefs.BackupSettings
import com.grid.app.core.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class BackupBusy { CONNECTING, BACKING_UP, FETCHING, RESTORING, SAVING_FILE }

data class BackupUiState(
    val backup: BackupSettings? = null,
    val busy: BackupBusy? = null,
    val notConfigured: Boolean = false,
    /** A validated archive waiting for the user's confirmation. */
    val pendingRestore: RestoredArchive? = null,
)

sealed interface BackupEvent {
    data class NeedsConsent(val intentSender: IntentSender) : BackupEvent
    data class Message(val text: String) : BackupEvent
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val auth: DriveAuth,
    private val drive: DriveBackup,
    private val manager: BackupManager,
) : ViewModel() {

    private val local = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = combine(settings.settings, local) { s, l -> l.copy(backup = s.backup) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    private val _events = Channel<BackupEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** What to do once Google consent comes back. */
    private enum class DriveAction { CONNECT, BACKUP, RESTORE }
    private var pendingAction: DriveAction? = null

    fun connect() = withDrive(DriveAction.CONNECT, BackupBusy.CONNECTING)
    fun backupNow() = withDrive(DriveAction.BACKUP, BackupBusy.BACKING_UP)
    fun restoreFromDrive() = withDrive(DriveAction.RESTORE, BackupBusy.FETCHING)

    fun onConsentResult(data: Intent?) {
        val action = pendingAction ?: return
        pendingAction = null
        viewModelScope.launch { handle(action, auth.fromConsentResult(data)) }
    }

    fun setFrequency(frequency: BackupFrequency) = viewModelScope.launch { settings.setBackupFrequency(frequency) }
    fun setWifiOnly(on: Boolean) = viewModelScope.launch { settings.setBackupWifiOnly(on) }
    fun disconnect() = viewModelScope.launch { settings.disconnectBackup() }

    fun saveFile(uri: Uri) = run(BackupBusy.SAVING_FILE) {
        withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { manager.writeArchive(it) } }
        message(R.string.backup_saved_file)
    }

    fun exportCsv(uri: Uri) = run(BackupBusy.SAVING_FILE) {
        val csv = manager.csv()
        withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } }
        message(R.string.backup_saved_csv)
    }

    fun openFile(uri: Uri) = run(BackupBusy.FETCHING) {
        val restored = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { manager.inspect(it) }
        } ?: throw BackupException(BackupException.Reason.CORRUPT, "")
        local.update { it.copy(pendingRestore = restored) }
    }

    fun cancelRestore() = local.update { it.copy(pendingRestore = null) }

    fun confirmRestore() {
        val restored = local.value.pendingRestore ?: return
        run(BackupBusy.RESTORING) {
            manager.apply(restored)
            manager.restartApp()
        }
    }

    private fun withDrive(action: DriveAction, busy: BackupBusy) = run(busy) {
        handle(action, auth.authorize())
    }

    private suspend fun handle(action: DriveAction, result: DriveAuthResult) {
        when (result) {
            is DriveAuthResult.Token -> {
                local.update { it.copy(notConfigured = false, busy = busyFor(action)) }
                try {
                    when (action) {
                        DriveAction.CONNECT -> {
                            settings.setBackupConnected(null)
                            drive.backupNow(result.accessToken)
                            message(R.string.backup_done, formatSize(state.value.backup?.lastSizeBytes ?: 0))
                        }
                        DriveAction.BACKUP -> {
                            val size = drive.backupNow(result.accessToken)
                            message(R.string.backup_done, formatSize(size))
                        }
                        DriveAction.RESTORE -> {
                            val restored = drive.fetchLatest(result.accessToken)
                            local.update { it.copy(pendingRestore = restored) }
                        }
                    }
                } finally {
                    local.update { it.copy(busy = null) }
                }
            }
            is DriveAuthResult.NeedsConsent -> {
                pendingAction = action
                local.update { it.copy(busy = null) }
                _events.send(BackupEvent.NeedsConsent(result.intentSender))
            }
            DriveAuthResult.NotConfigured -> local.update { it.copy(notConfigured = true, busy = null) }
            is DriveAuthResult.Failed -> {
                local.update { it.copy(busy = null) }
                _events.send(BackupEvent.Message(result.message))
            }
        }
    }

    private fun busyFor(action: DriveAction) = when (action) {
        DriveAction.CONNECT -> BackupBusy.CONNECTING
        DriveAction.BACKUP -> BackupBusy.BACKING_UP
        DriveAction.RESTORE -> BackupBusy.FETCHING
    }

    private fun run(busy: BackupBusy, block: suspend () -> Unit) = viewModelScope.launch {
        local.update { it.copy(busy = busy) }
        try {
            block()
        } catch (e: BackupException) {
            _events.send(BackupEvent.Message(errorText(e)))
        } catch (e: Exception) {
            _events.send(BackupEvent.Message(e.message ?: e.javaClass.simpleName))
        } finally {
            local.update { it.copy(busy = null) }
        }
    }

    private suspend fun message(res: Int, vararg args: Any) = _events.send(BackupEvent.Message(context.getString(res, *args)))

    private fun errorText(e: BackupException): String = when (e.reason) {
        BackupException.Reason.CORRUPT -> context.getString(R.string.backup_error_corrupt)
        BackupException.Reason.NEWER_VERSION -> context.getString(R.string.backup_error_newer)
        BackupException.Reason.NO_BACKUP -> context.getString(R.string.backup_error_none)
        else -> e.message.orEmpty()
    }

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }
}
