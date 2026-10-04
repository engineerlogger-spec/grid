package com.grid.app.feature.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.BuildConfig
import com.grid.app.R
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.LocalMoneyFormatter
import com.grid.app.core.designsystem.components.Segmented
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.ThemeMode
import com.grid.app.core.money.Currencies
import com.grid.app.core.time.BudgetPeriods
import com.grid.app.feature.common.CurrencyPicker
import com.grid.app.feature.common.MoneyField
import com.grid.app.feature.common.moneyFieldText
import com.grid.app.feature.common.parseMoney

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state.settings ?: return
    val colors = GridTheme.colors
    val formatter = LocalMoneyFormatter.current
    var currencySheet by remember { mutableStateOf(false) }
    var goalDialog by remember { mutableStateOf(false) }
    var dayMenu by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
        }

        CapsLabel(stringResource(R.string.settings_appearance), Modifier.padding(start = 4.dp, top = 8.dp))
        Tile(contentPadding = PaddingValues(16.dp)) {
            SettingRow(Icons.Rounded.Palette, stringResource(R.string.settings_theme))
            Spacer(Modifier.height(10.dp))
            Segmented(
                options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                selected = s.themeMode,
                label = { stringResource(when (it) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark }) },
                onSelect = viewModel::setTheme,
                modifier = Modifier.fillMaxWidth(),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Spacer(Modifier.height(14.dp))
                SettingRow(
                    icon = Icons.Rounded.Palette,
                    title = stringResource(R.string.settings_dynamic_color),
                    body = stringResource(R.string.settings_dynamic_color_body),
                    trailing = { Switch(checked = s.dynamicColor, onCheckedChange = viewModel::setDynamicColor) },
                )
            }
        }

        CapsLabel(stringResource(R.string.settings_money), Modifier.padding(start = 4.dp, top = 8.dp))
        Tile(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            SettingRow(
                icon = Icons.Rounded.Payments,
                title = stringResource(R.string.settings_currency),
                body = "${s.currency} · ${Currencies.displayName(s.currency)}",
                onClick = { currencySheet = true },
            )
            SettingRow(
                icon = Icons.Rounded.Flag,
                title = stringResource(R.string.settings_goal),
                body = state.goalMinor?.let { formatter.format(it, s.currency) } ?: "—",
                onClick = { goalDialog = true },
            )
            SettingRow(
                icon = Icons.Rounded.CalendarMonth,
                title = stringResource(R.string.settings_period_start),
                body = stringResource(R.string.settings_period_start_body, s.periodStartDay),
                onClick = { dayMenu = true },
            )
        }

        CapsLabel(stringResource(R.string.settings_privacy), Modifier.padding(start = 4.dp, top = 8.dp))
        Tile(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            SettingRow(
                icon = Icons.Rounded.VisibilityOff,
                title = stringResource(R.string.settings_hide_amounts),
                body = stringResource(R.string.settings_hide_amounts_body),
                trailing = { Switch(checked = s.hideAmounts, onCheckedChange = viewModel::setHideAmounts) },
            )
        }

        CapsLabel(stringResource(R.string.settings_about), Modifier.padding(start = 4.dp, top = 8.dp))
        Tile(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            SettingRow(Icons.Rounded.Info, "Grid", stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
        }
        Spacer(Modifier.height(24.dp))
    }

    if (currencySheet) {
        ModalBottomSheet(onDismissRequest = { currencySheet = false }, containerColor = colors.background) {
            CurrencyPicker(s.currency, { viewModel.setCurrency(it); currencySheet = false }, Modifier.padding(horizontal = 16.dp).height(520.dp))
        }
    }
    if (dayMenu) {
        StartDayDialog(selected = s.periodStartDay, onPick = { viewModel.setPeriodStartDay(it); dayMenu = false }, onDismiss = { dayMenu = false })
    }
    if (goalDialog) {
        var text by remember { mutableStateOf(moneyFieldText(state.goalMinor, s.currency)) }
        AlertDialog(
            onDismissRequest = { goalDialog = false },
            title = { Text(stringResource(R.string.settings_goal)) },
            text = { MoneyField(text, { text = it }, s.currency, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    parseMoney(text, s.currency)?.takeIf { it > 0 }?.let(viewModel::setGoal)
                    goalDialog = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { goalDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Days 1–28 as a 7×4 grid — every option visible at once (no scrolling a 28-item menu). */
@Composable
private fun StartDayDialog(selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val colors = GridTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_period_start)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (BudgetPeriods.MIN_START_DAY..BudgetPeriods.MAX_START_DAY).chunked(7).forEach { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        week.forEach { day ->
                            val isSelected = day == selected
                            Surface(
                                onClick = { onPick(day) },
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else colors.raised,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else colors.text,
                            ) {
                                Box(contentAlignment = Alignment.Center) { Text(day.toString(), style = GridText.moneySmall) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    body: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = GridTheme.colors
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.text)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        trailing?.invoke()
    }
}
