package com.grid.app.feature.bills

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.designsystem.components.GridChip
import com.grid.app.core.designsystem.components.MonogramBadge
import com.grid.app.core.designsystem.components.Segmented
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.CycleUnit
import com.grid.app.core.model.SubscriptionStatus
import com.grid.app.feature.common.CategoryDropdown
import com.grid.app.feature.common.ColorPicker
import com.grid.app.feature.common.DateField
import com.grid.app.feature.common.FormSection
import com.grid.app.feature.common.GridTextField
import com.grid.app.feature.common.MethodDropdown
import com.grid.app.feature.common.MoneyField
import com.grid.app.feature.common.ReminderChips

@Composable
fun SubscriptionEditorScreen(onDone: () -> Unit, viewModel: SubscriptionEditorViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val colors = GridTheme.colors
    LaunchedEffect(s.done) { if (s.done) onDone() }
    if (s.loading) return

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            Text(
                stringResource(if (s.isNew) R.string.sub_new_title else R.string.sub_edit_title),
                style = MaterialTheme.typography.titleLarge, color = colors.text, modifier = Modifier.weight(1f),
            )
            if (!s.isNew) {
                IconButton(onClick = viewModel::delete) { Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.action_delete), tint = colors.danger) }
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (s.isNew && s.lastPaid != null) {
                Text(
                    pluralStringResource(R.plurals.sub_from_payment, s.paymentIds.size, s.paymentIds.size, s.name),
                    style = MaterialTheme.typography.bodyMedium, color = colors.muted,
                )
            }
            if (s.isNew && s.lastPaid == null) {
                FormSection(stringResource(R.string.sub_popular)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SubscriptionPresets.all.forEach { preset ->
                            GridChip(
                                label = preset.name,
                                leading = { MonogramBadge(preset.name, preset.colorKey, size = 20.dp) },
                                selected = s.name == preset.name,
                                onClick = { viewModel.applyPreset(preset) },
                            )
                        }
                    }
                }
            }
            Tile {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonogramBadge(s.name.ifBlank { "?" }, s.colorKey, size = 52.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GridTextField(s.name, viewModel::setName, stringResource(R.string.sub_name), isError = s.nameError)
                        MoneyField(s.amount, viewModel::setAmount, s.currency, modifier = Modifier.fillMaxWidth(), label = stringResource(R.string.sub_amount), isError = s.amountError)
                    }
                }
            }
            FormSection(stringResource(R.string.sub_cycle)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CycleChoice.entries.forEach { choice ->
                        GridChip(
                            label = when (choice) {
                                CycleChoice.WEEKLY -> stringResource(R.string.cycle_weekly)
                                CycleChoice.MONTHLY -> stringResource(R.string.cycle_monthly)
                                CycleChoice.QUARTERLY -> stringResource(R.string.cycle_quarterly)
                                CycleChoice.YEARLY -> stringResource(R.string.cycle_yearly)
                                CycleChoice.CUSTOM -> stringResource(R.string.cycle_custom)
                            },
                            selected = s.choice == choice,
                            onClick = { viewModel.setChoice(choice) },
                        )
                    }
                }
                if (s.choice == CycleChoice.CUSTOM) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { viewModel.setCustomCount(s.customCount - 1) }) { Icon(Icons.Rounded.Remove, contentDescription = null) }
                        Text(s.customCount.toString(), style = GridText.moneyMedium, color = colors.text)
                        IconButton(onClick = { viewModel.setCustomCount(s.customCount + 1) }) { Icon(Icons.Rounded.Add, contentDescription = null) }
                        Segmented(
                            options = CycleUnit.entries,
                            selected = s.customUnit,
                            label = { stringResource(when (it) { CycleUnit.WEEK -> R.string.unit_weeks; CycleUnit.MONTH -> R.string.unit_months; CycleUnit.YEAR -> R.string.unit_years }) },
                            onSelect = viewModel::setCustomUnit,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            FormSection(stringResource(R.string.sub_next_charge)) { DateField(s.nextCharge, { it?.let(viewModel::setNextCharge) }, "") }
            FormSection(stringResource(R.string.sub_category)) { CategoryDropdown(s.categories, s.categoryId, viewModel::setCategory) }
            FormSection(stringResource(R.string.sub_method)) { MethodDropdown(s.methods, s.methodId, viewModel::setMethod) }
            FormSection(stringResource(R.string.sub_remind)) { ReminderChips(s.remindDays, viewModel::setRemind) }
            Tile {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.sub_auto_log), style = MaterialTheme.typography.titleSmall, color = colors.text)
                        Text(stringResource(R.string.sub_auto_log_body), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    }
                    Switch(checked = s.autoLog, onCheckedChange = viewModel::setAutoLog)
                }
            }
            FormSection(stringResource(R.string.sub_color)) { ColorPicker(s.colorKey, viewModel::setColor) }
            if (!s.isNew) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.status == SubscriptionStatus.ACTIVE) {
                        OutlinedButton(onClick = { viewModel.setStatus(SubscriptionStatus.PAUSED) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(stringResource(R.string.sub_pause)) }
                        OutlinedButton(onClick = { viewModel.setStatus(SubscriptionStatus.CANCELLED) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(stringResource(R.string.sub_cancel)) }
                    } else {
                        OutlinedButton(onClick = { viewModel.setStatus(SubscriptionStatus.ACTIVE) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(stringResource(R.string.sub_resume)) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = viewModel::save,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text(stringResource(R.string.action_save), style = MaterialTheme.typography.titleSmall) }
    }
}

