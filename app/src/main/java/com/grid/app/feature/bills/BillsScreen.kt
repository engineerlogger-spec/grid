package com.grid.app.feature.bills

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.designsystem.components.AmountText
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.CategoryBadge
import com.grid.app.core.designsystem.components.EmptyState
import com.grid.app.core.designsystem.components.HeroTile
import com.grid.app.core.designsystem.components.LocalMoneyFormatter
import com.grid.app.core.designsystem.components.MonogramBadge
import com.grid.app.core.designsystem.components.MoneyText
import com.grid.app.core.designsystem.components.Segmented
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.CycleUnit
import com.grid.app.core.model.PendingDirection
import com.grid.app.core.model.PendingPayment
import com.grid.app.core.model.Subscription
import com.grid.app.core.bills.ChargeState
import com.grid.app.core.bills.MonthCharge
import com.grid.app.core.model.SubscriptionStatus
import com.grid.app.feature.common.LocalMessenger
import com.grid.app.feature.common.shortDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class BillsTab { SUBSCRIPTIONS, PENDING }

@Composable
fun BillsScreen(
    contentPadding: PaddingValues,
    onEditSubscription: (Long?) -> Unit,
    onEditPending: (Long?) -> Unit,
    viewModel: BillsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(BillsTab.SUBSCRIPTIONS) }
    val colors = GridTheme.colors

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bills_title), style = MaterialTheme.typography.headlineMedium, color = colors.text, modifier = Modifier.weight(1f))
                Surface(
                    onClick = { if (tab == BillsTab.SUBSCRIPTIONS) onEditSubscription(null) else onEditPending(null) },
                    shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = stringResource(if (tab == BillsTab.SUBSCRIPTIONS) R.string.bills_add_subscription else R.string.bills_add_pending),
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        item {
            Segmented(
                options = BillsTab.entries,
                selected = tab,
                label = { stringResource(if (it == BillsTab.SUBSCRIPTIONS) R.string.bills_subscriptions else R.string.bills_pending) },
                onSelect = { tab = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.hasReminders) item { NotificationPermissionBanner() }
        if (!state.loading) {
            when (tab) {
                BillsTab.SUBSCRIPTIONS -> subscriptionsContent(state, onEditSubscription)
                BillsTab.PENDING -> pendingContent(state, onEditPending, viewModel)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.subscriptionsContent(state: BillsUiState, onEdit: (Long?) -> Unit) {
    if (state.active.isEmpty() && state.inactive.isEmpty()) {
        item {
            EmptyState(
                Icons.Rounded.Autorenew,
                stringResource(R.string.bills_no_subs_title),
                stringResource(R.string.bills_no_subs_body),
                action = { Button(onClick = { onEdit(null) }, shape = RoundedCornerShape(14.dp)) { Text(stringResource(R.string.bills_add_subscription)) } },
            )
        }
    }
    if (state.active.isNotEmpty()) {
        item { SubscriptionSummary(state) }
    }
    items(state.active, key = { "s${it.id}" }) { sub -> SubscriptionRow(sub, state.today, charge = state.charges[sub.id]) { onEdit(sub.id) } }
    if (state.inactive.isNotEmpty()) {
        item { CapsLabel(stringResource(R.string.bills_inactive), Modifier.padding(start = 4.dp, top = 10.dp)) }
        items(state.inactive, key = { "i${it.id}" }) { sub -> SubscriptionRow(sub, state.today, dimmed = true) { onEdit(sub.id) } }
    }
}

@Composable
private fun SubscriptionSummary(state: BillsUiState) {
    val colors = GridTheme.colors
    val formatter = LocalMoneyFormatter.current
    val next = state.active.minByOrNull { it.nextCharge }
    HeroTile {
        CapsLabel(stringResource(R.string.bills_per_month), color = colors.heroMuted)
        MoneyText(state.monthlyMinor, state.currency, style = GridText.moneyHero, color = colors.heroText, fractionColor = colors.heroMuted)
        Text(
            stringResource(R.string.bills_per_year, formatter.format(state.yearlyMinor, state.currency, masked = com.grid.app.core.designsystem.components.LocalHideAmounts.current)) +
                " · " + pluralStringResource(R.plurals.bills_active_count, state.active.size, state.active.size),
            style = MaterialTheme.typography.labelMedium, color = colors.heroMuted,
        )
        if (state.chargingCount > 0) {
            Spacer(Modifier.height(10.dp))
            val left = state.leftThisMonthMinor
            Text(
                if (left == 0L && state.paidCount == state.chargingCount) stringResource(R.string.bills_month_all_paid)
                else stringResource(R.string.bills_month_paid, state.paidCount, state.chargingCount) + " · " +
                    stringResource(R.string.bills_month_left, formatter.format(left, state.currency, masked = com.grid.app.core.designsystem.components.LocalHideAmounts.current)),
                style = MaterialTheme.typography.labelMedium, color = colors.accent,
            )
        } else if (next != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.bills_next, next.name, relativeDay(next.nextCharge, state.today)),
                style = MaterialTheme.typography.labelMedium, color = colors.accent,
            )
        }
    }
}

@Composable
private fun SubscriptionRow(sub: Subscription, today: LocalDate, dimmed: Boolean = false, charge: MonthCharge? = null, onClick: () -> Unit) {
    val colors = GridTheme.colors
    Tile(onClick = onClick, modifier = Modifier.alpha(if (dimmed) 0.55f else 1f), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonogramBadge(sub.name, sub.colorKey, size = 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(sub.name, style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when (sub.status) {
                    SubscriptionStatus.ACTIVE -> cycleLabel(sub) + " · " + relativeDay(sub.nextCharge, today)
                    SubscriptionStatus.PAUSED -> stringResource(R.string.bills_paused)
                    SubscriptionStatus.CANCELLED -> stringResource(R.string.bills_cancelled)
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1)
                if (sub.detected) {
                    Text(
                        stringResource(if (sub.amountVaries) R.string.bills_detected_varies else R.string.bills_detected),
                        style = MaterialTheme.typography.labelSmall, color = colors.accentText, maxLines = 1,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                AmountText(sub.amountMinor, sub.currency, style = GridText.moneySmall)
                charge?.let { ChargeLabel(it) }
            }
        }
    }
}

/** This month: "Paid 3 Oct" (from the bank's payment), "Due 15 Oct", or "Not paid · due 1 Oct". */
@Composable
private fun ChargeLabel(charge: MonthCharge) {
    val colors = GridTheme.colors
    val day = java.time.format.DateTimeFormatter.ofPattern("d MMM", com.grid.app.core.designsystem.components.currentLocale())
    val (text, color) = when (charge.state) {
        ChargeState.PAID -> stringResource(R.string.bills_paid_on, charge.paidOn!!.format(day)) to colors.income
        ChargeState.DUE -> stringResource(R.string.bills_due_on, charge.dueOn!!.format(day)) to colors.muted
        ChargeState.LATE -> stringResource(R.string.bills_late, charge.dueOn!!.format(day)) to colors.warning
        ChargeState.NONE -> return
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
}

private fun androidx.compose.foundation.lazy.LazyListScope.pendingContent(state: BillsUiState, onEdit: (Long?) -> Unit, vm: BillsViewModel) {
    if (state.toPay.isEmpty() && state.owedToMe.isEmpty() && state.settled.isEmpty()) {
        item {
            EmptyState(
                Icons.Rounded.Check,
                stringResource(R.string.bills_no_pending_title),
                stringResource(R.string.bills_no_pending_body),
                action = { Button(onClick = { onEdit(null) }, shape = RoundedCornerShape(14.dp)) { Text(stringResource(R.string.bills_add_pending)) } },
            )
        }
    }
    if (state.toPay.isNotEmpty()) {
        item { PendingTotalHeader(stringResource(R.string.bills_to_pay), state.toPay, state.currency) }
        items(state.toPay, key = { "p${it.id}" }) { p -> PendingRow(p, state.today, vm, onEdit) }
    }
    if (state.owedToMe.isNotEmpty()) {
        item { PendingTotalHeader(stringResource(R.string.bills_owed_to_me), state.owedToMe, state.currency) }
        items(state.owedToMe, key = { "o${it.id}" }) { p -> PendingRow(p, state.today, vm, onEdit) }
    }
    if (state.settled.isNotEmpty()) {
        item { CapsLabel(stringResource(R.string.bills_settled), Modifier.padding(start = 4.dp, top = 10.dp)) }
        items(state.settled, key = { "d${it.id}" }) { p -> PendingRow(p, state.today, vm, onEdit, settled = true) }
    }
}

@Composable
private fun PendingTotalHeader(title: String, items: List<PendingPayment>, currency: String) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CapsLabel(title, Modifier.weight(1f))
        AmountText(items.filter { it.currency == currency }.sumOf { it.amountMinor }, currency, style = GridText.moneyTiny, color = GridTheme.colors.muted)
    }
}

@Composable
private fun PendingRow(p: PendingPayment, today: LocalDate, vm: BillsViewModel, onEdit: (Long?) -> Unit, settled: Boolean = false) {
    val colors = GridTheme.colors
    val messenger = LocalMessenger.current
    val formatter = LocalMoneyFormatter.current
    val owe = p.direction == PendingDirection.I_OWE
    val overdue = !settled && p.due != null && p.due.isBefore(today)
    val dueSoon = !settled && p.due != null && !overdue && ChronoUnit.DAYS.between(today, p.due) <= 3
    val paidSnack = stringResource(if (owe) R.string.bills_paid_snack else R.string.bills_received_snack, formatter.format(p.amountMinor, p.currency))
    val undo = stringResource(R.string.action_undo)
    Tile(
        onClick = { onEdit(p.id) },
        modifier = Modifier.alpha(if (settled) 0.55f else 1f),
        borderColor = when { overdue -> colors.danger.copy(alpha = 0.6f); dueSoon -> colors.warning.copy(alpha = 0.5f); else -> colors.hairline },
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val category = p.category
            if (category != null) CategoryBadge(category.iconKey, category.colorKey, size = 40.dp)
            else Surface(shape = RoundedCornerShape(12.dp), color = colors.raised, modifier = Modifier.size(40.dp)) {
                Icon(if (owe) Icons.Rounded.NorthEast else Icons.Rounded.SouthWest, contentDescription = null, tint = if (owe) colors.warning else colors.income, modifier = Modifier.padding(10.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val dueText = when {
                    settled -> p.settledAt?.let { shortDate(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()) }.orEmpty()
                    p.due == null -> stringResource(R.string.bills_no_due)
                    overdue -> pluralStringResource(R.plurals.bills_days_overdue, ChronoUnit.DAYS.between(p.due, today).toInt(), ChronoUnit.DAYS.between(p.due, today).toInt())
                    else -> relativeDay(p.due, today)
                }
                Text(
                    listOfNotNull(p.counterparty, dueText).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = when { overdue -> colors.danger; dueSoon -> colors.warning; else -> colors.muted },
                    maxLines = 1,
                )
            }
            AmountText(p.amountMinor, p.currency, style = GridText.moneySmall, color = if (owe) colors.text else colors.income)
        }
        if (!settled) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { vm.settle(p.id); messenger.show(paidSnack, undo) { vm.reopen(p.id) } },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.hairline),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(if (owe) R.string.bills_mark_paid else R.string.bills_mark_received))
            }
        }
    }
}

@Composable
private fun NotificationPermissionBanner() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) return
    val colors = GridTheme.colors
    Tile(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }, borderColor = colors.warning.copy(alpha = 0.5f), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = colors.warning)
            Text(stringResource(R.string.notifications_rationale), style = MaterialTheme.typography.bodyMedium, color = colors.text, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
fun relativeDay(date: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, date).toInt()
    return when {
        days == 0 -> stringResource(R.string.bills_today)
        days == 1 -> stringResource(R.string.bills_tomorrow)
        days in 2..13 -> pluralStringResource(R.plurals.bills_in_days, days, days)
        else -> shortDate(date)
    }
}

@Composable
fun cycleLabel(sub: Subscription): String = cycleLabel(sub.cycle.unit, sub.cycle.count)

@Composable
fun cycleLabel(unit: CycleUnit, count: Int): String = when {
    unit == CycleUnit.WEEK && count == 1 -> stringResource(R.string.cycle_weekly)
    unit == CycleUnit.MONTH && count == 1 -> stringResource(R.string.cycle_monthly)
    unit == CycleUnit.MONTH && count == 3 -> stringResource(R.string.cycle_quarterly)
    unit == CycleUnit.YEAR && count == 1 -> stringResource(R.string.cycle_yearly)
    unit == CycleUnit.WEEK -> pluralStringResource(R.plurals.cycle_every_weeks, count, count)
    unit == CycleUnit.MONTH -> pluralStringResource(R.plurals.cycle_every_months, count, count)
    else -> pluralStringResource(R.plurals.cycle_every_years, count, count)
}
