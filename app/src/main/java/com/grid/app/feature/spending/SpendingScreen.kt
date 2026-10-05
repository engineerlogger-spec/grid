package com.grid.app.feature.spending

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.designsystem.charts.RatioBar
import com.grid.app.core.designsystem.components.AmountText
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.CategoryBadge
import com.grid.app.core.designsystem.components.Donut
import com.grid.app.core.designsystem.components.DonutSlice
import com.grid.app.core.designsystem.components.EmptyState
import com.grid.app.core.designsystem.components.MoneyText
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.feature.common.title
import kotlin.math.roundToInt

/** Where the month's money went: every category with spending, biggest first, compared with the month before. */
@Composable
fun SpendingScreen(
    onBack: () -> Unit,
    onOpenCategory: (categoryId: Long, monthEpochDay: Long) -> Unit,
    viewModel: SpendingViewModel = hiltViewModel(),
) {
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
                Text(stringResource(R.string.spending_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
            }
        }
        if (period == null) return@LazyColumn
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::previous) { Icon(Icons.Rounded.ChevronLeft, contentDescription = stringResource(R.string.moved_previous)) }
                Text(
                    period.title(state.today), style = MaterialTheme.typography.titleMedium, color = colors.text,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                )
                IconButton(onClick = viewModel::next, enabled = state.canGoNext) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = stringResource(R.string.moved_next))
                }
            }
        }
        if (state.rows.isEmpty()) {
            item { EmptyState(Icons.Rounded.DonutLarge, stringResource(R.string.spending_empty_title), stringResource(R.string.spending_empty_body)) }
            return@LazyColumn
        }
        item { TotalTile(state) }
        items(state.rows, key = { it.category.id }) { row ->
            CategoryRow(row, state, onClick = { onOpenCategory(row.category.id, period.start.toEpochDay()) })
        }
    }
}

@Composable
private fun TotalTile(state: SpendingUi) {
    val colors = GridTheme.colors
    Tile(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Donut(
                slices = state.rows.map { DonutSlice(it.fraction, colors.category(it.category.colorKey).fg) },
                size = 200.dp, thickness = 24.dp, trackColor = colors.raised, gapDegrees = 2.5f,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MoneyText(state.totalMinor, state.currency, style = GridText.moneyMedium, fractionColor = colors.muted)
                Text(stringResource(R.string.spending_total), style = MaterialTheme.typography.labelSmall, color = colors.muted)
            }
        }
        state.previousPeriod?.let { previous ->
            Spacer(Modifier.height(8.dp))
            Delta(
                state.totalMinor - state.previousTotalMinor, state.currency, previous.title(state.today),
                Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun CategoryRow(row: CategorySpend, state: SpendingUi, onClick: () -> Unit) {
    val colors = GridTheme.colors
    val tint = colors.category(row.category.colorKey).fg
    Tile(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(row.category.iconKey, row.category.colorKey, size = 38.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(row.category.name, style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(R.plurals.spending_payments, row.count, row.count) + " · ${(row.fraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall, color = colors.muted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                AmountText(row.amountMinor, state.currency, style = GridText.moneySmall, color = colors.text)
                state.previousPeriod?.let { previous ->
                    if (row.previousMinor == 0L) {
                        Text(stringResource(R.string.spending_new), style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    } else {
                        Delta(row.amountMinor - row.previousMinor, state.currency, previous.title(state.today))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        RatioBar(row.fraction, tint, colors.raised, thickness = 5.dp)
    }
}

/** "+€40 vs September": more spent is a warning, less is good news. */
@Composable
private fun Delta(deltaMinor: Long, currency: String, versus: String, modifier: Modifier = Modifier) {
    val colors = GridTheme.colors
    val color = when {
        deltaMinor > 0 -> colors.warning
        deltaMinor < 0 -> colors.income
        else -> colors.muted
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AmountText(deltaMinor, currency, style = GridText.moneyTiny, color = color, signed = true, compact = true)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.spending_vs, versus), style = MaterialTheme.typography.labelSmall, color = colors.muted)
    }
}
