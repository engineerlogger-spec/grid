package com.grid.app.feature.bank

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.bank.BankSync
import com.grid.app.core.data.db.entities.BankAccountEntity
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.GridChip
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.BankStatus
import com.grid.app.feature.common.FormSection
import com.grid.app.feature.common.GridTextField
import com.grid.app.feature.common.LocalMessenger
import com.grid.app.feature.common.PickerField
import com.grid.app.feature.common.shortDate
import com.grid.app.feature.common.timeOfDay
import com.grid.app.feature.common.toLocalDate
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun BankSetupScreen(onBack: () -> Unit, viewModel: BankSetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = GridTheme.colors
    val messenger = LocalMessenger.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val res = context.resources
            when (event) {
                is BankEvent.OpenUrl -> runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(event.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                is BankEvent.Synced -> messenger.show(
                    if (event.newItems == 0) res.getString(R.string.bank_synced_none) else res.getQuantityString(R.plurals.bank_synced, event.newItems, event.newItems),
                )
                BankEvent.RateLimited -> messenger.show(res.getString(R.string.bank_rate_limited))
                BankEvent.Expired -> messenger.show(res.getString(R.string.bank_expired_snack))
                is BankEvent.Failed -> messenger.show(res.getString(R.string.bank_failed, event.message))
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            Text(stringResource(R.string.bank_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.loading) return@Column

        when (state.phase) {
            BankPhase.KEY -> KeyStep(state, viewModel)
            BankPhase.CONNECT -> ConnectStep(state, viewModel)
            BankPhase.WAITING -> WaitingStep(state, viewModel)
            BankPhase.ACCOUNTS -> {
                CapsLabel(stringResource(R.string.bank_accounts_title), Modifier.padding(start = 4.dp, top = 4.dp))
                AccountsTile(state, viewModel)
                Button(onClick = viewModel::startSync, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(stringResource(R.string.bank_start_sync))
                }
            }
            BankPhase.CONNECTED -> ConnectedStep(state, viewModel, onShare = {
                scope.launch {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, viewModel.rawData())
                    context.startActivity(Intent.createChooser(send, null))
                }
            })
        }

        state.error?.let { error ->
            Text(errorText(error), style = MaterialTheme.typography.bodyMedium, color = colors.warning, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun KeyStep(state: BankSetupUi, viewModel: BankSetupViewModel) {
    val context = LocalContext.current
    val colors = GridTheme.colors
    var appIdInput by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importKey(it, appIdInput) } }
    val copied = stringResource(R.string.bank_copied)
    val messenger = LocalMessenger.current

    Text(stringResource(R.string.bank_intro), style = MaterialTheme.typography.bodyLarge, color = colors.muted, modifier = Modifier.padding(horizontal = 4.dp))
    Tile {
        Step(1, stringResource(R.string.bank_step1)) {
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ENABLE_BANKING_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.bank_open_site)) }
        }
        Step(2, stringResource(R.string.bank_step2)) {
            Surface(color = colors.background, shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(BankSync.REDIRECT_URL, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colors.text, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Redirect URL", BankSync.REDIRECT_URL))
                        messenger.show(copied)
                    }) { Text(stringResource(R.string.bank_copy)) }
                }
            }
        }
        Step(3, stringResource(R.string.bank_step3))
        Step(4, stringResource(R.string.bank_step4), last = true) {
            GridTextField(appIdInput, { appIdInput = it }, placeholder = stringResource(R.string.bank_app_id_hint))
            Button(
                onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp), shape = RoundedCornerShape(14.dp),
            ) { Text(stringResource(R.string.bank_import_key)) }
        }
    }
    if (viewModel.isDebug) {
        TextButton(onClick = viewModel::useDemo) { Text(stringResource(R.string.bank_use_demo)) }
    }
}

@Composable
private fun ConnectStep(state: BankSetupUi, viewModel: BankSetupViewModel) {
    val reconnect = state.accounts.isNotEmpty()
    var backfill by remember { mutableStateOf(Backfill.MONTHS_3) }
    FormSection(stringResource(R.string.bank_country)) { CountryPicker(state.country, viewModel::setCountry) }
    if (!reconnect) {
        FormSection(stringResource(R.string.bank_history)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Backfill.PERIOD to R.string.bank_history_period,
                    Backfill.MONTHS_3 to R.string.bank_history_3m,
                    Backfill.MONTHS_12 to R.string.bank_history_12m,
                ).forEach { (value, label) -> GridChip(label = stringResource(label), selected = backfill == value, onClick = { backfill = value }) }
            }
        }
    }
    Button(
        onClick = { viewModel.connect(if (reconnect) null else backfill) }, enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp), shape = RoundedCornerShape(14.dp),
    ) { Text(stringResource(if (reconnect) R.string.bank_reconnect else R.string.bank_connect)) }
    TextButton(onClick = viewModel::changeKey) { Text(stringResource(R.string.bank_change_key)) }
}

@Composable
private fun WaitingStep(state: BankSetupUi, viewModel: BankSetupViewModel) {
    val colors = GridTheme.colors
    var pasted by remember { mutableStateOf("") }
    var notALink by remember { mutableStateOf(false) }
    Tile {
        Text(stringResource(R.string.bank_waiting_title), style = MaterialTheme.typography.titleMedium, color = colors.text)
        Text(stringResource(R.string.bank_waiting_body), style = MaterialTheme.typography.bodyMedium, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
        OutlinedButton(onClick = { viewModel.connect(null) }, enabled = !state.busy, modifier = Modifier.padding(top = 12.dp), shape = RoundedCornerShape(12.dp)) {
            Text(stringResource(R.string.bank_retry))
        }
    }
    FormSection(stringResource(R.string.bank_paste)) {
        GridTextField(pasted, { pasted = it; notALink = false }, placeholder = stringResource(R.string.bank_paste_hint), isError = notALink)
        Button(
            onClick = { notALink = !viewModel.pasteLink(pasted) }, enabled = pasted.isNotBlank() && !state.busy,
            modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
        ) { Text(stringResource(R.string.bank_paste)) }
    }
}

@Composable
private fun ConnectedStep(state: BankSetupUi, viewModel: BankSetupViewModel, onShare: () -> Unit) {
    val colors = GridTheme.colors
    val connection = state.connection ?: return
    val expired = connection.status == BankStatus.EXPIRED
    var confirmDisconnect by remember { mutableStateOf(false) }

    Tile(borderColor = if (expired) colors.warning.copy(alpha = 0.6f) else colors.accentText.copy(alpha = 0.5f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (expired) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, contentDescription = null,
                tint = if (expired) colors.warning else colors.accentText,
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(connection.aspspName, style = MaterialTheme.typography.titleMedium, color = colors.text)
                Text(statusLine(connection.status, connection.lastSyncAt, connection.validUntil), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                connection.lastError?.takeIf { !expired }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warning)
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (expired) {
                Button(onClick = { viewModel.connect(null) }, enabled = !state.busy, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.bank_reconnect)) }
            } else {
                Button(onClick = viewModel::syncNow, enabled = !state.busy, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.bank_sync_now)) }
                OutlinedButton(onClick = { viewModel.connect(null) }, enabled = !state.busy, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.bank_reconnect)) }
            }
        }
    }
    CapsLabel(stringResource(R.string.bank_accounts_title), Modifier.padding(start = 4.dp, top = 4.dp))
    AccountsTile(state, viewModel)
    Row {
        TextButton(onClick = onShare) { Text(stringResource(R.string.bank_share_raw)) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { confirmDisconnect = true }) { Text(stringResource(R.string.bank_disconnect), color = colors.warning) }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.bank_disconnect)) },
            text = { Text(stringResource(R.string.bank_disconnect_confirm)) },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; viewModel.disconnect() }) { Text(stringResource(R.string.bank_disconnect)) } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun AccountsTile(state: BankSetupUi, viewModel: BankSetupViewModel) {
    val colors = GridTheme.colors
    Tile {
        state.accounts.forEach { account ->
            val supported = account.currency == state.appCurrency
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(accountName(account), style = MaterialTheme.typography.titleSmall, color = colors.text)
                    Text(
                        if (supported) account.currency else stringResource(R.string.bank_account_other_currency, account.currency),
                        style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    )
                }
                Switch(checked = account.enabled && supported, enabled = supported, onCheckedChange = { viewModel.setEnabled(account, it) })
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String, last: Boolean = false, content: (@Composable () -> Unit)? = null) {
    val colors = GridTheme.colors
    Row(Modifier.padding(bottom = if (last) 0.dp else 14.dp)) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Surface(shape = CircleShape, color = colors.accentText.copy(alpha = 0.18f), modifier = Modifier.size(26.dp)) {}
            Text("$number", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.accentText)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.text, modifier = Modifier.padding(top = 3.dp))
            content?.invoke()
        }
    }
}

@Composable
private fun CountryPicker(selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PickerField(text = countryName(selected), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BankSetupViewModel.COUNTRIES.sortedBy { countryName(it) }.forEach { code ->
                DropdownMenuItem(text = { Text(countryName(code)) }, onClick = { onPick(code); open = false })
            }
        }
    }
}

@Composable
private fun statusLine(status: BankStatus, lastSyncAt: Long?, validUntil: Long?): String {
    if (status == BankStatus.EXPIRED) return stringResource(R.string.bank_status_expired)
    val synced = lastSyncAt?.let {
        val date = it.toLocalDate()
        stringResource(R.string.bank_status_synced, if (date == LocalDate.now()) timeOfDay(it) else shortDate(date))
    } ?: stringResource(R.string.bank_status_never)
    val days = validUntil?.let { TimeUnit.MILLISECONDS.toDays(it - System.currentTimeMillis()).toInt().coerceAtLeast(0) }
    return if (days == null) synced else synced + " · " + pluralStringResource(R.plurals.bank_days_left, days, days)
}

@Composable
private fun errorText(failure: BankFailure): String {
    if (failure.kind == BankSetupError.BANK_REFUSED) return stringResource(R.string.bank_err_bank, failure.detail.orEmpty())
    val text = stringResource(
        when (failure.kind) {
            BankSetupError.BAD_KEY -> R.string.bank_err_bad_key
            BankSetupError.NEED_APP_ID -> R.string.bank_err_app_id
            BankSetupError.KEY_REFUSED -> R.string.bank_err_refused
            BankSetupError.NO_REVOLUT -> R.string.bank_err_no_revolut
            BankSetupError.NOT_GRANTED -> R.string.bank_err_not_granted
            BankSetupError.NETWORK, BankSetupError.BANK_REFUSED -> R.string.bank_err_network
        },
    )
    // The bank's own words help tell a wrong key from a wrong setting.
    return failure.detail?.takeIf { failure.kind == BankSetupError.KEY_REFUSED }?.let { "$text ($it)" } ?: text
}

private fun accountName(account: BankAccountEntity): String =
    account.name ?: account.iban?.let { "•••• " + it.takeLast(4) } ?: account.currency

private fun countryName(code: String): String = Locale("", code).getDisplayCountry(Locale.getDefault()).ifBlank { code }

private const val ENABLE_BANKING_URL = "https://enablebanking.com/"
