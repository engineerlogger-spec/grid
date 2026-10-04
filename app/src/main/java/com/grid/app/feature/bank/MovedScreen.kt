package com.grid.app.feature.bank

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.EmptyState
import com.grid.app.core.designsystem.components.MoneyText
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.feature.common.shortDate
import com.grid.app.feature.common.title

/** "Moved to Revolut": money taken from the salary account, per month, with each transfer's month adjustable. */
@Composable
fun MovedScreen(onBack: () -> Unit, viewModel: MovedViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = GridTheme.colors
    val period = state.period

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                Text(stringResource(R.string.moved_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
            }
        }
        if (period == null) return@LazyColumn
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::previous) { Icon(Icons.Rounded.ChevronLeft, contentDescription = stringResource(R.string.moved_previous)) }
                Text(
                    period.title(state.today), style = MaterialTheme.typography.titleMedium, color = colors.text,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = viewModel::next) { Icon(Icons.Rounded.ChevronRight, contentDescription = stringResource(R.string.moved_next)) }
            }
        }
        item { SavingsSummary(state) }
        if (state.rows.isEmpty()) {
            item { EmptyState(Icons.Rounded.SwapVert, stringResource(R.string.moved_empty_title), stringResource(R.string.moved_empty_body)) }
        } else {
            item { CapsLabel(stringResource(R.string.moved_transfers), Modifier.padding(start = 4.dp, top = 6.dp)) }
            items(state.rows, key = { it.transfer.id }) { row -> TransferRow(row, state, onNextMonth = { viewModel.setNextMonth(row, it) }) }
            item {
                Text(stringResource(R.string.moved_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun SavingsSummary(state: MovedUi) {
    val colors = GridTheme.colors
    Tile {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Figure(stringResource(R.string.moved_salary), state.salaryMinor, state.currency)
            Figure(stringResource(R.string.moved_moved), state.movedMinor, state.currency)
            Figure(stringResource(R.string.moved_saved), state.savedMinor, state.currency, highlight = true)
        }
        if (state.salaryMinor == 0L) {
            Text(stringResource(R.string.moved_no_salary), style = MaterialTheme.typography.bodySmall, color = colors.warning, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Figure(label: String, amountMinor: Long, currency: String, highlight: Boolean = false) {
    val colors = GridTheme.colors
    Column {
        CapsLabel(label, color = if (highlight) colors.accentText else colors.muted)
        MoneyText(
            amountMinor, currency, style = GridText.moneySmall, fractionColor = colors.muted,
            color = when {
                highlight && amountMinor < 0 -> colors.warning
                highlight -> colors.accentText
                else -> colors.text
            },
        )
    }
}

@Composable
private fun TransferRow(row: MovedRow, state: MovedUi, onNextMonth: (Boolean) -> Unit) {
    val colors = GridTheme.colors
    val t = row.transfer
    Tile(contentPadding = PaddingValues(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (t.incoming) Icons.Rounded.SouthWest else Icons.Rounded.NorthEast, contentDescription = null,
                tint = if (t.incoming) colors.income else colors.muted, modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    t.counterparty ?: stringResource(R.string.moved_unknown), style = MaterialTheme.typography.titleSmall,
                    color = if (row.countedHere) colors.text else colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        !row.datedHere -> stringResource(R.string.moved_from_previous, shortDate(t.date))
                        !row.countedHere -> stringResource(R.string.moved_counted_next, shortDate(t.date))
                        else -> shortDate(t.date)
                    },
                    style = MaterialTheme.typography.bodySmall, color = colors.muted,
                )
            }
            MoneyText(
                if (t.incoming) t.amountMinor else -t.amountMinor, state.currency, style = GridText.moneySmall, signed = true,
                color = if (!row.countedHere) colors.muted else if (t.incoming) colors.income else colors.text, fractionColor = colors.muted,
            )
            if (row.datedHere) {
                // Ticked = counted in the next month (e.g. salary transferred on the 29th, meant for next month).
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Checkbox(checked = !row.countedHere, onCheckedChange = onNextMonth)
                    Text(stringResource(R.string.moved_next_month), style = MaterialTheme.typography.labelSmall, color = colors.muted)
                }
            } else {
                TextButton(onClick = { onNextMonth(false) }) { Text(stringResource(R.string.moved_return)) }
            }
        }
    }
}
