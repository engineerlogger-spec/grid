package com.grid.app.feature.bills

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
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.designsystem.components.Segmented
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.PendingDirection
import com.grid.app.core.model.PendingStatus
import com.grid.app.feature.common.CategoryDropdown
import com.grid.app.feature.common.DateField
import com.grid.app.feature.common.FormSection
import com.grid.app.feature.common.GridTextField
import com.grid.app.feature.common.MoneyField
import com.grid.app.feature.common.ReminderChips

@Composable
fun PendingEditorScreen(onDone: () -> Unit, viewModel: PendingEditorViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val colors = GridTheme.colors
    LaunchedEffect(s.done) { if (s.done) onDone() }
    if (s.loading) return

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            Text(
                stringResource(if (s.isNew) R.string.pending_new_title else R.string.pending_edit_title),
                style = MaterialTheme.typography.titleLarge, color = colors.text, modifier = Modifier.weight(1f),
            )
            if (!s.isNew) {
                IconButton(onClick = viewModel::delete) { Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.action_delete), tint = colors.danger) }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Segmented(
                options = PendingDirection.entries,
                selected = s.direction,
                label = { stringResource(if (it == PendingDirection.I_OWE) R.string.pending_i_owe else R.string.pending_owed_to_me) },
                onSelect = viewModel::setDirection,
                modifier = Modifier.fillMaxWidth(),
            )
            GridTextField(s.title, viewModel::setTitle, stringResource(R.string.pending_title_hint), isError = s.showErrors && s.title.isBlank())
            GridTextField(s.counterparty, viewModel::setCounterparty, stringResource(R.string.pending_counterparty))
            MoneyField(s.amount, viewModel::setAmount, s.currency, modifier = Modifier.fillMaxWidth(), label = stringResource(R.string.sub_amount), isError = s.showErrors && s.amountMinor == null)
            FormSection(stringResource(R.string.pending_due)) { DateField(s.due, viewModel::setDue, stringResource(R.string.pending_no_due), allowClear = true) }
            FormSection(stringResource(R.string.sub_remind)) { ReminderChips(s.remindDays, viewModel::setRemind) }
            FormSection(stringResource(R.string.sub_category)) { CategoryDropdown(s.categories, s.categoryId, viewModel::setCategory, noneLabel = "—") }
            GridTextField(s.note, viewModel::setNote, stringResource(R.string.pending_note), singleLine = false)
            if (!s.isNew && s.status == PendingStatus.DONE) {
                OutlinedButton(onClick = viewModel::reopen, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(stringResource(R.string.pending_reopen)) }
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp), shape = RoundedCornerShape(16.dp)) {
            Text(stringResource(R.string.action_save), style = MaterialTheme.typography.titleSmall)
        }
    }
}
