package com.grid.app.feature.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.data.prefs.BackupFrequency
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.GridChip
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.feature.common.LocalMessenger
import com.grid.app.feature.common.shortDate
import com.grid.app.feature.common.timeOfDay
import com.grid.app.feature.common.toLocalDate
import java.time.LocalDate

@Composable
fun BackupScreen(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = GridTheme.colors
    val messenger = LocalMessenger.current
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { viewModel.onConsentResult(it.data) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(viewModel::saveFile) }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(viewModel::exportCsv) }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::openFile) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BackupEvent.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(event.intentSender).build())
                is BackupEvent.Message -> messenger.show(event.text)
            }
        }
    }
    val backup = state.backup ?: return
    val busy = state.busy

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
        }

        CapsLabel(stringResource(R.string.backup_drive), Modifier.padding(start = 4.dp, top = 4.dp))
        Tile {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (backup.connected) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff, contentDescription = null,
                    tint = if (backup.connected) colors.accentText else colors.muted, modifier = Modifier.size(28.dp),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        when {
                            backup.connected && backup.account != null -> stringResource(R.string.backup_connected_as, backup.account)
                            backup.connected -> stringResource(R.string.backup_connected)
                            else -> stringResource(R.string.backup_drive)
                        },
                        style = MaterialTheme.typography.titleSmall, color = colors.text,
                    )
                    Text(
                        backup.lastAt?.let { stringResource(R.string.backup_last, whenText(it), BackupViewModel.formatSize(backup.lastSizeBytes ?: 0)) }
                            ?: stringResource(R.string.backup_never),
                        style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    )
                    backup.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.danger) }
                }
            }
            if (!backup.connected) {
                Text(stringResource(R.string.backup_drive_body), style = MaterialTheme.typography.bodyMedium, color = colors.muted, modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { if (backup.connected) viewModel.backupNow() else viewModel.connect() },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (busy == BackupBusy.BACKING_UP || busy == BackupBusy.CONNECTING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.size(10.dp))
                    Text(stringResource(R.string.backup_working))
                } else {
                    Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (backup.connected) R.string.backup_now else R.string.backup_connect))
                }
            }
            OutlinedButton(
                onClick = viewModel::restoreFromDrive, enabled = busy == null,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(14.dp),
            ) { Text(stringResource(R.string.backup_restore_drive)) }

            if (state.notConfigured) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.backup_not_configured_title), style = MaterialTheme.typography.titleSmall, color = colors.warning)
                Text(stringResource(R.string.backup_not_configured_body), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }

            if (backup.connected) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.backup_frequency), style = MaterialTheme.typography.titleSmall, color = colors.text)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        BackupFrequency.OFF to R.string.backup_off, BackupFrequency.DAILY to R.string.backup_daily,
                        BackupFrequency.WEEKLY to R.string.backup_weekly, BackupFrequency.MONTHLY to R.string.backup_monthly,
                    ).forEach { (f, label) -> GridChip(stringResource(label), selected = backup.frequency == f, onClick = { viewModel.setFrequency(f) }) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.backup_wifi_only), style = MaterialTheme.typography.bodyMedium, color = colors.text, modifier = Modifier.weight(1f))
                    Switch(checked = backup.wifiOnly, onCheckedChange = viewModel::setWifiOnly)
                }
                TextButton(onClick = viewModel::disconnect) { Text(stringResource(R.string.backup_disconnect), color = colors.muted) }
            }
        }

        CapsLabel(stringResource(R.string.backup_local), Modifier.padding(start = 4.dp, top = 8.dp))
        Tile {
            Text(stringResource(R.string.backup_local_body), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            Spacer(Modifier.height(10.dp))
            LocalAction(Icons.Rounded.Save, stringResource(R.string.backup_save_file), busy == null) { saveFile.launch("grid-backup-${LocalDate.now()}.zip") }
            LocalAction(Icons.Rounded.FileOpen, stringResource(R.string.backup_open_file), busy == null) { openFile.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
            LocalAction(Icons.Rounded.Description, stringResource(R.string.backup_export_csv), busy == null) { saveCsv.launch("grid-transactions-${LocalDate.now()}.csv") }
        }
        Spacer(Modifier.height(16.dp))
    }

    state.pendingRestore?.let { restored ->
        val m = restored.manifest
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = {
                Text(stringResource(R.string.backup_restore_confirm_body, m.device, dateTimeText(m.createdAt), m.counts["transactions"] ?: 0))
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore, enabled = busy == null) {
                    Text(stringResource(if (busy == BackupBusy.RESTORING) R.string.backup_restoring else R.string.backup_restore_confirm), color = colors.danger)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelRestore) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun LocalAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(label, modifier = Modifier.weight(1f).padding(start = 12.dp), color = GridTheme.colors.text)
    }
}

private fun dateTimeText(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT))

private fun whenText(millis: Long): String {
    val date = millis.toLocalDate()
    return if (date == LocalDate.now()) timeOfDay(millis) else shortDate(date)
}
