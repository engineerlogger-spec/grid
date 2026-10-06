package com.grid.app.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import com.grid.app.core.designsystem.components.GridChip
import com.grid.app.core.bills.LowFundsState
import com.grid.app.core.notify.LowFundsText
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.designsystem.components.AmountText
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.CategoryBadge
import com.grid.app.core.designsystem.components.Donut
import com.grid.app.core.designsystem.components.DonutSlice
import com.grid.app.core.designsystem.components.EmptyState
import com.grid.app.core.designsystem.components.HeroTile
import com.grid.app.core.designsystem.components.LocalHideAmounts
import com.grid.app.core.designsystem.components.LocalMoneyFormatter
import com.grid.app.core.designsystem.components.MonogramBadge
import com.grid.app.core.designsystem.components.MoneyText
import com.grid.app.core.designsystem.components.MonthGrid
import com.grid.app.core.designsystem.components.SectionHeader
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.bills.UpcomingKind
import com.grid.app.core.insights.DashboardSummary
import com.grid.app.core.model.PendingDirection
import com.grid.app.feature.bills.relativeDay
import com.grid.app.feature.capture.sourceName
import com.grid.app.feature.common.LocalQuickAdd
import com.grid.app.feature.common.TransactionRow
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onOpenSettings: () -> Unit,
    onOpenActivity: (dayEpoch: Long?) -> Unit,
    onOpenCheckIn: () -> Unit,
    onOpenBills: () -> Unit,
    onOpenDetected: () -> Unit,
    onOpenBank: () -> Unit,
    onOpenMoved: () -> Unit,
    onOpenInsights: () -> Unit,
    /** Ask Grid, optionally with a first message already sent. */
    onOpenAsk: (prompt: String?) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val quickAdd = LocalQuickAdd.current
    val summary = state.summary ?: return

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Header(
                title = state.title,
                summary = summary,
                hidden = state.hideAmounts,
                onToggleHidden = viewModel::toggleHideAmounts,
                onOpenSettings = onOpenSettings,
            )
        }
        item { AskBar(onOpenAsk) }
        state.bankToReconnect?.let { bank ->
            item { ReconnectBanner(bank, onOpenBank) }
        }
        state.lowFunds?.let { short ->
            item { LowFundsBanner(short, state.hideAmounts, onOpenBills) }
        }
        if (state.needsCheckIn) {
            item { CheckInBanner(onOpenCheckIn) }
        }
        if (state.detectedCount > 0) {
            item { DetectedTile(state, onOpenDetected) }
        }
        if (state.otherToSort > 0) {
            item { SortLink(state.otherToSort, onOpenDetected) }
        }
        if (state.suggestedSubscriptions > 0) {
            item { SuggestedLink(state.suggestedSubscriptions) { com.grid.app.feature.bills.BillsTabRequest.next.value = com.grid.app.feature.bills.BillsTab.SUGGESTED; onOpenBills() } }
        }
        item {
            HeroCard(state, summary, onDayClick = { onOpenActivity(it.toEpochDay()) })
        }
        item {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SpentTile(summary, state.currency, Modifier.weight(1f).fillMaxHeight())
                IncomeTile(summary, state.currency, state.needsCheckIn, onOpenCheckIn, Modifier.weight(1f).fillMaxHeight())
            }
        }
        state.movedMinor?.let { moved ->
            item { SavingsTile(state.salaryMinor, moved, state.currency, onClick = onOpenMoved) }
        }
        item { UpcomingTile(state, onClick = onOpenBills) }
        item { CategoriesTile(state, onClick = onOpenInsights) }
        // Gemini's notes on the month (shown once a key is set).
        item { com.grid.app.feature.ai.DigestTile() }
        item { SectionHeader(stringResource(R.string.home_recent), Modifier.padding(top = 6.dp), action = stringResource(R.string.action_see_all), onAction = { onOpenActivity(null) }) }
        item {
            Tile(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                if (state.recent.isEmpty()) {
                    EmptyState(Icons.Rounded.WbSunny, stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body))
                } else {
                    state.recent.forEach { tx -> TransactionRow(tx, onClick = { quickAdd.edit(tx.id) }) }
                }
            }
        }
    }
}

/** "Gemini suggests 3 subscriptions · Review": opens Bills, where they wait for Add / Not a bill. */
@Composable
private fun SuggestedLink(count: Int, onClick: () -> Unit) {
    val colors = GridTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(16.dp))
        Text(
            pluralStringResource(R.plurals.home_suggested, count, count), style = MaterialTheme.typography.bodyMedium,
            color = colors.accentText, modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Ask Grid, front and centre: questions about the money, or things to do ("add…", "put … under …"). */
@Composable
private fun AskBar(onOpenAsk: (String?) -> Unit) {
    val colors = GridTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(onClick = { onOpenAsk(null) }, borderColor = colors.accentText.copy(alpha = 0.5f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(22.dp))
                Text(stringResource(R.string.home_ask_hint), style = MaterialTheme.typography.bodyLarge, color = colors.muted, modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.home_ask_week, R.string.home_ask_bills, R.string.home_ask_afford, R.string.home_ask_save).forEach { id ->
                val prompt = stringResource(id)
                GridChip(label = prompt, onClick = { onOpenAsk(prompt) })
            }
        }
    }
}

@Composable
private fun Header(title: String, summary: DashboardSummary, hidden: Boolean, onToggleHidden: () -> Unit, onOpenSettings: () -> Unit) {
    val colors = GridTheme.colors
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = colors.text)
            Text(
                stringResource(R.string.home_day_of, summary.dayNumber, summary.length) + " · " +
                    pluralStringResource(R.plurals.home_days_left, summary.daysLeft, summary.daysLeft),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        RoundIconButton(
            icon = if (hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            label = stringResource(if (hidden) R.string.action_show_amounts else R.string.action_hide_amounts),
            onClick = onToggleHidden,
        )
        Spacer(Modifier.width(8.dp))
        RoundIconButton(Icons.Rounded.Settings, stringResource(R.string.action_settings), onOpenSettings)
    }
}

@Composable
private fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val colors = GridTheme.colors
    Surface(onClick = onClick, shape = CircleShape, color = colors.tile, border = BorderStroke(1.dp, colors.hairline), modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = colors.muted, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun CheckInBanner(onClick: () -> Unit) {
    val colors = GridTheme.colors
    Tile(onClick = onClick, borderColor = colors.accentText.copy(alpha = 0.6f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_checkin_banner), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.home_checkin_banner_action), style = GridText.caps, color = colors.accentText)
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.accentText)
        }
    }
}

/** Bank payments Grid couldn't name a category for: already counted (under Other); sorting is optional. */
@Composable
private fun SortLink(count: Int, onClick: () -> Unit) {
    val colors = GridTheme.colors
    Tile(onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.home_other_to_sort, count, count),
                style = MaterialTheme.typography.bodyMedium, color = colors.muted, modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.home_sort), style = GridText.caps, color = colors.accentText)
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.accentText)
        }
    }
}

/** Salary entered − money moved to Revolut = what stayed in the salary account this month. */
@Composable
private fun SavingsTile(salaryMinor: Long, movedMinor: Long, currency: String, onClick: () -> Unit) {
    val colors = GridTheme.colors
    val saved = salaryMinor - movedMinor
    Tile(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapsLabel(stringResource(R.string.home_savings), Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.muted)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            SavingsFigure(stringResource(R.string.moved_salary), salaryMinor, currency, colors.text)
            SavingsFigure(stringResource(R.string.moved_moved), movedMinor, currency, colors.text)
            SavingsFigure(stringResource(R.string.moved_saved), saved, currency, if (saved < 0) colors.warning else colors.accentText)
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(if (salaryMinor > 0) movedMinor.toFloat() / salaryMinor else 0f, colors.accentText)
    }
}

@Composable
private fun SavingsFigure(label: String, amountMinor: Long, currency: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = GridTheme.colors.muted)
        MoneyText(amountMinor, currency, style = GridText.moneySmall, color = color, fractionColor = GridTheme.colors.muted)
    }
}

/** Open Banking consent ended: sync is paused until the user approves again. */
@Composable
private fun ReconnectBanner(bank: String, onClick: () -> Unit) {
    val colors = GridTheme.colors
    Tile(onClick = onClick, borderColor = colors.warning.copy(alpha = 0.7f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_bank_expired, bank), style = MaterialTheme.typography.titleSmall, color = colors.text)
                Text(stringResource(R.string.bank_reconnect_body), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            Text(stringResource(R.string.bank_reconnect), style = GridText.caps, color = colors.warning)
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.warning)
        }
    }
}

/** "€540 short by 5 Oct — not enough for Rent (€850) on 5 Oct. Move money to Revolut." Opens Bills. */
@Composable
private fun LowFundsBanner(short: LowFundsState, hideAmounts: Boolean, onClick: () -> Unit) {
    val colors = GridTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val formatter = LocalMoneyFormatter.current
    val (title, body) = LowFundsText.of(context, formatter, short, masked = hideAmounts)
    Tile(onClick = onClick, borderColor = colors.warning.copy(alpha = 0.8f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = colors.warning)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.text)
                Text(body, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.warning)
        }
    }
}

@Composable
private fun DetectedTile(state: HomeUiState, onClick: () -> Unit) {
    val colors = GridTheme.colors
    Tile(onClick = onClick, borderColor = colors.accentText.copy(alpha = 0.6f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CapsLabel(stringResource(R.string.detected_title), color = colors.accentText)
                Text(
                    pluralStringResource(R.plurals.detected_tile_count, state.detectedCount, state.detectedCount),
                    style = MaterialTheme.typography.titleSmall, color = colors.text,
                )
                Text(state.detectedSources.map { sourceName(it) }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            Text(stringResource(R.string.detected_review), style = GridText.caps, color = colors.accentText)
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.accentText)
        }
    }
}

@Composable
private fun HeroCard(state: HomeUiState, summary: DashboardSummary, onDayClick: (LocalDate) -> Unit) {
    val colors = GridTheme.colors
    val formatter = LocalMoneyFormatter.current
    val left = summary.leftMinor
    HeroTile {
        CapsLabel(
            stringResource(if (left != null && left < 0) R.string.home_over_budget else R.string.home_left_to_spend),
            color = colors.heroMuted,
        )
        MoneyText(
            minor = left?.let { kotlin.math.abs(it) } ?: summary.spentMinor,
            currency = state.currency,
            style = GridText.moneyHero,
            color = if (left != null && left < 0) Color(0xFFFF6B5A) else colors.heroText,
            fractionColor = colors.heroMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
        val perDayText = when {
            left == null -> stringResource(R.string.home_no_goal)
            left < 0 -> stringResource(R.string.home_over_by)
            else -> stringResource(R.string.home_per_day, formatter.format(summary.perDayMinor ?: 0, state.currency, masked = state.hideAmounts))
        }
        Text(
            perDayText,
            style = MaterialTheme.typography.labelMedium,
            color = if (left != null && left >= 0) colors.accent else colors.heroMuted,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
        )
        MonthGrid(state.cells, state.firstDayOfWeek, onDayClick = onDayClick)
    }
}

@Composable
private fun SpentTile(summary: DashboardSummary, currency: String, modifier: Modifier) {
    val colors = GridTheme.colors
    val goal = summary.goalMinor
    Tile(modifier) {
        CapsLabel(stringResource(R.string.home_spent))
        MoneyText(summary.spentMinor, currency, style = GridText.moneyLarge, fractionColor = colors.muted, modifier = Modifier.padding(top = 4.dp))
        Text(
            if (goal != null) stringResource(R.string.home_of_goal, LocalMoneyFormatter.current.compact(goal, currency, LocalHideAmounts.current)) else stringResource(R.string.home_no_goal_short),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f).height(10.dp))
        val fraction = if (goal != null && goal > 0) summary.spentMinor.toFloat() / goal else 0f
        ProgressBar(fraction, if (fraction > 1f) colors.danger else if (colors.isDark) colors.accent else colors.accentText)
    }
}

@Composable
private fun IncomeTile(summary: DashboardSummary, currency: String, needsCheckIn: Boolean, onCheckIn: () -> Unit, modifier: Modifier) {
    val colors = GridTheme.colors
    Tile(modifier, onClick = if (needsCheckIn) onCheckIn else null) {
        CapsLabel(stringResource(R.string.home_income))
        MoneyText(summary.incomeMinor, currency, style = GridText.moneyLarge, fractionColor = colors.muted, modifier = Modifier.padding(top = 4.dp))
        Text(
            stringResource(if (needsCheckIn) R.string.home_income_missing else R.string.home_income_confirmed),
            style = MaterialTheme.typography.bodySmall,
            color = if (needsCheckIn) colors.warning else colors.muted,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f).height(10.dp))
        val saved = if (summary.incomeMinor > 0) 1f - summary.spentMinor.toFloat() / summary.incomeMinor else 0f
        ProgressBar(if (summary.incomeMinor > 0) 1f else 0f, colors.income, secondary = saved.coerceIn(0f, 1f))
    }
}

@Composable
private fun ProgressBar(fraction: Float, color: Color, secondary: Float? = null) {
    val colors = GridTheme.colors
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.raised)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
        if (secondary != null && secondary > 0f) {
            // Portion of income not yet spent, drawn brighter on top of the income bar.
            Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(secondary).fillMaxHeight().background(color.copy(alpha = 0.35f)))
        }
    }
}

@Composable
private fun UpcomingTile(state: HomeUiState, onClick: () -> Unit) {
    val colors = GridTheme.colors
    val formatter = LocalMoneyFormatter.current
    Tile(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapsLabel(stringResource(R.string.home_upcoming), Modifier.weight(1f))
            if (state.upcomingDueMinor > 0) {
                Text(
                    stringResource(R.string.home_upcoming_due, formatter.format(state.upcomingDueMinor, state.currency, masked = LocalHideAmounts.current)),
                    style = GridText.caps, color = colors.warning,
                )
            }
        }
        if (state.upcoming.isEmpty()) {
            Text(stringResource(R.string.home_nothing_due), style = MaterialTheme.typography.bodyMedium, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
        } else {
            state.upcoming.take(3).forEach { item ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.kind == UpcomingKind.SUBSCRIPTION || item.iconKey == null) MonogramBadge(item.title, item.colorKey, size = 32.dp)
                    else CategoryBadge(item.iconKey, item.colorKey, size = 32.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                item.overdue -> stringResource(R.string.bills_overdue)
                                // Forecast from the bank history, not a bill the user entered.
                                item.kind == UpcomingKind.FORECAST -> stringResource(R.string.home_upcoming_expected, relativeDay(item.date, state.today))
                                else -> relativeDay(item.date, state.today)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.overdue) colors.danger else colors.muted,
                        )
                    }
                    AmountText(
                        if (item.direction == PendingDirection.OWED_TO_ME) item.amountMinor else -item.amountMinor,
                        item.currency, style = GridText.moneySmall, signed = item.direction == PendingDirection.OWED_TO_ME,
                        color = if (item.direction == PendingDirection.OWED_TO_ME) colors.income else colors.text,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoriesTile(state: HomeUiState, onClick: () -> Unit) {
    val colors = GridTheme.colors
    Tile(onClick = onClick) {
        CapsLabel(stringResource(R.string.home_top_categories))
        Spacer(Modifier.height(10.dp))
        if (state.topCategories.isEmpty()) {
            Text(stringResource(R.string.home_no_spending_yet), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Donut(
                    slices = state.topCategories.map { DonutSlice(it.fraction, colors.category(it.category.colorKey).fg) } +
                        listOfNotNull((1f - state.topCategories.sumOf { it.fraction.toDouble() }.toFloat()).takeIf { it > 0.005f }?.let { DonutSlice(it, colors.hairline) }),
                    size = 72.dp,
                    thickness = 11.dp,
                    trackColor = colors.raised,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.topCategories.forEach { slice ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CategoryBadge(slice.category.iconKey, slice.category.colorKey, size = 24.dp)
                            Text(slice.category.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${(slice.fraction * 100).roundToInt()}%", style = GridText.moneyTiny, color = colors.muted)
                            AmountText(slice.amountMinor, state.currency, style = GridText.moneyTiny, compact = true)
                        }
                    }
                }
            }
        }
    }
}

