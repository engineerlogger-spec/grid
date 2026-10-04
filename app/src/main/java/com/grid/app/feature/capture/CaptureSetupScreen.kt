package com.grid.app.feature.capture

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.grid.app.R
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.CaptureSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CaptureSetupViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    val capture = settings.settings.map { it.capture }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun setSource(source: CaptureSource, on: Boolean) = viewModelScope.launch { settings.setCaptureSource(source, on) }
    fun setAutoAdd(on: Boolean) = viewModelScope.launch { settings.setCaptureAutoAdd(on) }
    fun setDiagnostics(on: Boolean) = viewModelScope.launch { settings.setCaptureDiagnostics(on) }
}

@Composable
fun CaptureSetupScreen(onBack: () -> Unit, viewModel: CaptureSetupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val colors = GridTheme.colors
    val capture by viewModel.capture.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(PaymentCaptureService.isEnabled(context)) }
    val installed = remember { PaymentCaptureService.installedSources(context) }
    LifecycleResumeEffect(Unit) {
        enabled = PaymentCaptureService.isEnabled(context)
        onPauseOrDispose {}
    }
    val c = capture ?: return

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            Text(stringResource(R.string.capture_setup_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
        }
        Text(stringResource(R.string.capture_setup_body), style = MaterialTheme.typography.bodyLarge, color = colors.muted, modifier = Modifier.padding(horizontal = 4.dp))

        Tile(borderColor = if (enabled) colors.accentText.copy(alpha = 0.5f) else colors.warning.copy(alpha = 0.6f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (enabled) Icons.Rounded.CheckCircle else Icons.Rounded.NotificationsActive, contentDescription = null,
                    tint = if (enabled) colors.accentText else colors.warning,
                )
                Text(
                    stringResource(if (enabled) R.string.capture_status_on else R.string.capture_status_off),
                    style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (!enabled) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, PaymentCaptureService.component(context).flattenToString())
                        } else {
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        }
                        runCatching { context.startActivity(intent) }.onFailure { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.capture_grant)) }
            }
        }

        if (!enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Tile {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.capture_restricted_title), style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.padding(start = 10.dp))
                }
                Text(stringResource(R.string.capture_restricted_body), style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(top = 6.dp))
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) },
                    modifier = Modifier.padding(top = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.capture_app_info)) }
            }
        }

        CapsLabel(stringResource(R.string.capture_sources), Modifier.padding(start = 4.dp, top = 6.dp))
        Tile {
            CaptureSource.entries.forEach { source ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sourceName(source), style = MaterialTheme.typography.titleSmall, color = colors.text)
                        Text(
                            stringResource(if (source in installed) R.string.capture_installed else R.string.capture_not_installed),
                            style = MaterialTheme.typography.bodySmall, color = colors.muted,
                        )
                    }
                    Switch(checked = c.enabled(source), onCheckedChange = { viewModel.setSource(source, it) })
                }
            }
        }
        Tile {
            ToggleRow(stringResource(R.string.capture_auto_add), stringResource(R.string.capture_auto_add_body), c.autoAdd, viewModel::setAutoAdd)
            Spacer(Modifier.height(8.dp))
            ToggleRow(stringResource(R.string.capture_diagnostics), stringResource(R.string.capture_diagnostics_body), c.diagnostics, viewModel::setDiagnostics)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = GridTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.text)
            Text(body, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
