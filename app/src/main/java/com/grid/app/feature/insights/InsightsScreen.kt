package com.grid.app.feature.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.designsystem.charts.BarPair
import com.grid.app.core.designsystem.charts.BarPairsChart
import com.grid.app.core.designsystem.charts.PaceChart
import com.grid.app.core.designsystem.charts.RatioBar
import com.grid.app.core.designsystem.components.AmountText
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.CategoryBadge
import com.grid.app.core.designsystem.components.Donut
import com.grid.app.core.designsystem.components.DonutSlice
import com.grid.app.core.designsystem.components.EmptyState
import com.grid.app.core.designsystem.components.LocalHideAmounts
import com.grid.app.core.designsystem.components.LocalMoneyFormatter
import com.grid.app.core.designsystem.components.MethodBadge
import com.grid.app.core.designsystem.components.MoneyText
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.components.currentLocale
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.insights.Insight
import com.grid.app.core.insights.InsightsReport
import com.grid.app.feature.common.CategoryDropdown
import com.grid.app.feature.common.MoneyField
import com.grid.app.feature.common.moneyFieldText
import com.grid.app.feature.common.parseMoney
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun InsightsScreen(
    contentPadding: PaddingValues,
    onOpenCategory: (Long) -> Unit,
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var budgetDialog by remember { mutableStateOf<Long?>(null) } // category id, or -1 for "pick"
    val colors = GridTheme.colors
    val report = state.report

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.insights_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
                    Text(state.title, style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
                IconButton(onClick = viewModel::previous) { Icon(Icons.Rounded.ChevronLeft, contentDescription = null, tint = colors.muted) }
                IconButton(onClick = viewModel::next, enabled = state.canGoNext) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = if (state.canGoNext) colors.muted else colors.faint)
                }
            }
        }
        if (report == null) return@LazyColumn
        item { KpiGrid(report, state.currency) }
        if (report.kpis.spentMinor == 0L) {
            item { EmptyState(Icons.Rounded.Insights, stringResource(R.string.insights_empty_title), stringResource(R.string.insights_empty_body)) }
        }
        if (report.insights.isNotEmpty()) {
            items(report.insights.size) { i -> InsightCard(report.insights[i], state) }
        }
        if (report.byCategory.isNotEmpty()) item { CategoryBreakdown(state, report, onOpenCategory) }
        if (report.kpis.elapsedDays > 0 && report.kpis.spentMinor > 0) item { PaceTile(state, report) }
        item { HistoryTile(state, report) }
        item { BudgetsTile(state, report, onSet = { budgetDialog = it }) }
        if (report.byMethod.isNotEmpty()) item { MethodsTile(state, report) }
        if (report.topMerchants.isNotEmpty()) item { MerchantsTile(state, report) }
    }

    budgetDialog?.let { initial ->
        BudgetDialog(
            state = state,
            initialCategoryId = initial.takeIf { it > 0 },
            onSave = { id, limit -> viewModel.setBudget(id, limit); budgetDialog = null },
            onDismiss = { budgetDialog = null },
        )
    }
}

@Composable
private fun KpiGrid(report: InsightsReport, currency: String) {
    val colors = GridTheme.colors
    val k = report.kpis
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi(stringResource(R.string.insights_spent), k.spentMinor, currency, Modifier.weight(1f).fillMaxHeight())
            Kpi(stringResource(R.string.insights_income), k.incomeMinor, currency, Modifier.weight(1f).fillMaxHeight(), color = colors.income)
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi(stringResource(R.string.insights_saved), k.savedMinor, currency, Modifier.weight(1f).fillMaxHeight(), signed = true, color = if (k.savedMinor < 0) colors.danger else colors.accentText)
            Kpi(stringResource(R.string.insights_daily_avg), k.dailyAverageMinor, currency, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun Kpi(label: String, minor: Long, currency: String, modifier: Modifier, signed: Boolean = false, color: androidx.compose.ui.graphics.Color = GridTheme.colors.text) {
    Tile(modifier) {
        CapsLabel(label)
        MoneyText(minor, currency, style = GridText.moneyMedium, color = color, fractionColor = color.copy(alpha = 0.55f), signed = signed, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun InsightCard(insight: Insight, state: InsightsUiState) {
    val colors = GridTheme.colors
    val formatter = LocalMoneyFormatter.current
    val masked = LocalHideAmounts.current
    fun money(v: Long) = formatter.format(v, state.currency, masked = masked)
    val (icon: ImageVector, text: String, tint) = when (insight) {
        is Insight.CategoryIncrease -> Triple(
            Icons.Rounded.Whatshot,
            stringResource(R.string.insight_category_up, state.categories[insight.categoryId]?.name.orEmpty(), money(insight.deltaMinor)),
            colors.warning,
        )
        is Insight.ProjectionOver -> Triple(
            Icons.Rounded.AutoGraph,
            stringResource(R.string.insight_projection_over, money(insight.projectedMinor), money(insight.projectedMinor - insight.goalMinor)),
            colors.danger,
        )
        is Insight.ProjectionUnder -> Triple(
            Icons.Rounded.TrendingDown,
            stringResource(R.string.insight_projection_under, money(insight.projectedMinor), money(insight.goalMinor - insight.projectedMinor)),
            colors.accentText,
        )
        is Insight.HighestDay -> Triple(
            Icons.Rounded.EventBusy,
            stringResource(R.string.insight_highest_day, insight.date.format(DateTimeFormatter.ofPattern("EEE d MMM", currentLocale())), money(insight.amountMinor)),
            colors.muted,
        )
        is Insight.SubscriptionsShare -> Triple(
            Icons.Rounded.Autorenew,
            stringResource(R.string.insight_subscriptions, (insight.fraction * 100).roundToInt(), money(insight.amountMinor)),
            colors.category("violet").fg,
        )
        is Insight.SavingRate -> Triple(
            Icons.Rounded.Savings,
            if (insight.fraction >= 0) stringResource(R.string.insight_saved_rate, (insight.fraction * 100).roundToInt())
            else stringResource(R.string.insight_overspent_rate, (abs(insight.fraction) * 100).roundToInt()),
            if (insight.fraction >= 0) colors.accentText else colors.danger,
        )
    }
    Tile(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = tint.copy(alpha = 0.15f), modifier = Modifier.size(34.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.text, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun CategoryBreakdown(state: InsightsUiState, report: InsightsReport, onOpenCategory: (Long) -> Unit) {
    val colors = GridTheme.colors
    Tile {
        CapsLabel(stringResource(R.string.insights_by_category))
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Donut(
                slices = report.byCategory.map { DonutSlice(it.fraction, colors.category(state.categories[it.id]?.colorKey ?: "gray").fg) },
                size = 180.dp, thickness = 22.dp, trackColor = colors.raised, gapDegrees = 2.5f,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MoneyText(report.kpis.spentMinor, state.currency, style = GridText.moneyMedium, fractionColor = colors.muted)
                Text(stringResource(R.string.insights_total_spent), style = MaterialTheme.typography.labelSmall, color = colors.muted)
            }
        }
        Spacer(Modifier.height(14.dp))
        report.byCategory.forEach { share ->
            val category = state.categories[share.id] ?: return@forEach
            Row(
                Modifier.fillMaxWidth().clickable { onOpenCategory(share.id) }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryBadge(category.iconKey, category.colorKey, size = 30.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row {
                        Text(category.name, style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${(share.fraction * 100).roundToInt()}%", style = GridText.moneyTiny, color = colors.muted)
                    }
                    Spacer(Modifier.height(5.dp))
                    RatioBar(share.fraction, colors.category(category.colorKey).fg, colors.raised, thickness = 4.dp)
                }
                AmountText(share.amountMinor, state.currency, style = GridText.moneySmall, compact = true)
            }
        }
    }
}

@Composable
private fun PaceTile(state: InsightsUiState, report: InsightsReport) {
    val colors = GridTheme.colors
    val formatter = LocalMoneyFormatter.current
    val masked = LocalHideAmounts.current
    val pace = report.pace
    Tile {
        CapsLabel(stringResource(R.string.insights_pace))
        val endDate = state.period?.endExclusive?.minusDays(1)
        Text(
            if (report.isCurrent && endDate != null) stringResource(R.string.insights_pace_projection, formatter.format(pace.projectedMinor, state.currency, masked = masked), endDate.format(DateTimeFormatter.ofPattern("d MMM", currentLocale())))
            else stringResource(R.string.insights_pace_final, formatter.format(report.kpis.spentMinor, state.currency, masked = masked)),
            style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        PaceChart(
            cumulative = pace.cumulative, length = pace.length, goalMinor = pace.goalMinor, projectedMinor = pace.projectedMinor,
            lineColor = if (colors.isDark) colors.accent else colors.accentText, budgetColor = colors.muted, projectionColor = colors.muted, gridColor = colors.hairline,
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(if (colors.isDark) colors.accent else colors.accentText, stringResource(R.string.insights_pace_legend_spent))
            if (pace.goalMinor != null) Legend(colors.muted, stringResource(R.string.insights_pace_legend_budget))
        }
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = 3.dp).padding(0.dp)) { Surface(color = color, modifier = Modifier.fillMaxSize()) {} }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = GridTheme.colors.muted)
    }
}

@Composable
private fun HistoryTile(state: InsightsUiState, report: InsightsReport) {
    val colors = GridTheme.colors
    Tile {
        CapsLabel(stringResource(R.string.insights_history))
        Spacer(Modifier.height(12.dp))
        BarPairsChart(
            items = report.history.mapIndexed { i, t ->
                BarPair(state.historyLabels.getOrElse(i) { "" }, t.spentMinor, t.incomeMinor, highlighted = i == report.history.lastIndex)
            },
            firstColor = if (colors.isDark) colors.accent else colors.accentText,
            secondColor = colors.income,
            labelColor = colors.muted,
            highlightLabelColor = colors.text,
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(if (colors.isDark) colors.accent else colors.accentText, stringResource(R.string.insights_spent))
            Legend(colors.income, stringResource(R.string.insights_income))
        }
    }
}

@Composable
private fun BudgetsTile(state: InsightsUiState, report: InsightsReport, onSet: (Long) -> Unit) {
    val colors = GridTheme.colors
    val formatter = LocalMoneyFormatter.current
    val masked = LocalHideAmounts.current
    Tile {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapsLabel(stringResource(R.string.insights_budgets), Modifier.weight(1f))
            TextButton(onClick = { onSet(-1) }) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.insights_set_budget))
            }
        }
        if (report.budgets.isEmpty()) {
            Text(stringResource(R.string.insights_no_budgets), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        report.budgets.forEach { b ->
            val category = state.categories[b.categoryId] ?: return@forEach
            val over = b.spentMinor > b.limitMinor
            Column(Modifier.fillMaxWidth().clickable { onSet(b.categoryId) }.padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(category.iconKey, category.colorKey, size = 26.dp)
                    Text(category.name, style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    Text(
                        stringResource(
                            if (over) R.string.insights_budget_over else R.string.insights_budget_left,
                            formatter.format(abs(b.limitMinor - b.spentMinor), state.currency, masked = masked),
                        ),
                        style = GridText.moneyTiny, color = if (over) colors.danger else colors.muted,
                    )
                }
                Spacer(Modifier.height(6.dp))
                RatioBar(b.fraction, if (b.fraction >= 0.8f) colors.warning else colors.category(category.colorKey).fg, colors.raised, overColor = colors.danger)
            }
        }
    }
}

@Composable
private fun MethodsTile(state: InsightsUiState, report: InsightsReport) {
    val colors = GridTheme.colors
    Tile {
        CapsLabel(stringResource(R.string.insights_methods))
        report.byMethod.forEach { m ->
            val method = m.methodId?.let { state.methods[it] }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (method != null) MethodBadge(method.kind) else Spacer(Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(method?.name ?: stringResource(R.string.insights_unknown_method), style = MaterialTheme.typography.bodyMedium, color = colors.text)
                    Spacer(Modifier.height(4.dp))
                    RatioBar(m.fraction, colors.text.copy(alpha = 0.7f), colors.raised, thickness = 4.dp)
                }
                AmountText(m.amountMinor, state.currency, style = GridText.moneyTiny, compact = true)
            }
        }
    }
}

@Composable
private fun MerchantsTile(state: InsightsUiState, report: InsightsReport) {
    val colors = GridTheme.colors
    Tile {
        CapsLabel(stringResource(R.string.insights_merchants))
        report.topMerchants.forEachIndexed { i, m ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = GridText.moneySmall, color = colors.faint, modifier = Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.title, style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pluralStringResource(R.plurals.insights_times, m.count, m.count), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
                AmountText(m.amountMinor, state.currency, style = GridText.moneySmall, compact = true)
            }
        }
    }
}

@Composable
private fun BudgetDialog(state: InsightsUiState, initialCategoryId: Long?, onSave: (Long, Long?) -> Unit, onDismiss: () -> Unit) {
    var categoryId by remember { mutableStateOf(initialCategoryId ?: state.expenseCategories.firstOrNull()?.id) }
    val current = state.categories[categoryId]?.monthlyLimitMinor
    var amount by remember(categoryId) { mutableStateOf(moneyFieldText(current, state.currency)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.insights_set_budget)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryDropdown(state.expenseCategories, categoryId, { categoryId = it ?: categoryId })
                MoneyField(amount, { amount = it }, state.currency, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { categoryId?.let { onSave(it, parseMoney(amount, state.currency)?.takeIf { v -> v > 0 }) } }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { categoryId?.let { onSave(it, null) } }) { Text(stringResource(R.string.insights_budget_remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
