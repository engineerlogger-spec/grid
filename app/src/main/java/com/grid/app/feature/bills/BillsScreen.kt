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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.grid.app.core.designsystem.components.CategoryBadge
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
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

enum class BillsTab { SUBSCRIPTIONS, SUGGESTED, PENDING }

/** Lets another screen open Bills on a given tab (Home's "Gemini suggests…" opens Suggested). */
object BillsTabRequest {
    val next = kotlinx.coroutines.flow.MutableStateFlow<BillsTab?>(null)
}

@Composable
fun BillsScreen(
    contentPadding: PaddingValues,
    onEditSubscription: (Long?) -> Unit,
    onEditPending: (Long?) -> Unit,
    viewModel: BillsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(BillsTab.SUBSCRIPTIONS) }
    val requested by BillsTabRequest.next.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(requested) { requested?.let { tab = it; BillsTabRequest.next.value = null } }
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
                    onClick = { if (tab == BillsTab.PENDING) onEditPending(null) else onEditSubscription(null) },
                    shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = stringResource(if (tab == BillsTab.PENDING) R.string.bills_add_pending else R.string.bills_add_subscription),
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        item {
            Segmented(
                options = BillsTab.entries,
                selected = tab,
                label = {
                    when (it) {
                        BillsTab.SUBSCRIPTIONS -> stringResource(R.string.bills_subscriptions)
                        BillsTab.SUGGESTED -> stringResource(R.string.bills_suggested_tab) + if (state.suggested.isNotEmpty()) " · ${state.suggested.size}" else ""
                        BillsTab.PENDING -> stringResource(R.string.bills_pending)
                    }
                },
                onSelect = { tab = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.hasReminders) item { NotificationPermissionBanner() }
        if (!state.loading) {
            when (tab) {
                BillsTab.SUBSCRIPTIONS -> subscriptionsContent(state, onEditSubscription)
                BillsTab.SUGGESTED -> suggestedContent(state, viewModel)
                BillsTab.PENDING -> pendingContent(state, onEditPending, viewModel)
            }
        }
    }
}

/** What the user tracks, by category. Gemini's suggestions have their own tab. */
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
        return
    }
    if (state.active.isNotEmpty()) item { SubscriptionSummary(state) }
    items(state.activeGroups, key = { "ag${it.category.id}" }) { group ->
        GroupTile(group, state.currency) {
            group.subs.forEachIndexed { i, sub ->
                if (i > 0) Divider()
                SubscriptionLine(sub, state.today, state.charges[sub.id], onClick = { onEdit(sub.id) })
            }
        }
    }
    if (state.inactive.isNotEmpty()) {
        item { CapsLabel(stringResource(R.string.bills_inactive), Modifier.padding(start = 4.dp, top = 10.dp)) }
        item {
            Tile(modifier = Modifier.alpha(0.6f), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                state.inactive.forEachIndexed { i, sub ->
                    if (i > 0) Divider()
                    SubscriptionLine(sub, state.today, null, onClick = { onEdit(sub.id) })
                }
            }
        }
    }
}

/** Gemini's suggestions on their own: found in the payments, not yet tracked, waiting for Add / Not a bill. */
private fun androidx.compose.foundation.lazy.LazyListScope.suggestedContent(state: BillsUiState, vm: BillsViewModel) {
    item { FindTile(vm) }
    if (state.suggested.isEmpty()) {
        item { EmptyState(Icons.Rounded.AutoAwesome, stringResource(R.string.bills_no_suggestions_title), stringResource(R.string.bills_no_suggestions_body)) }
        return
    }
    item { SuggestionsHeader(state.suggested.size, onAddAll = vm::addAllSuggested) }
    items(state.suggestedGroups, key = { "sg${it.category.id}" }) { group ->
        GroupTile(group, state.currency, suggested = true) {
            group.subs.forEachIndexed { i, sub ->
                if (i > 0) Divider()
                SuggestionLine(sub, state.today, onAdd = { vm.addSuggested(sub.id) }, onReject = { vm.rejectSuggested(sub.id) })
            }
        }
    }
}

/** One category's card: its badge, name and monthly total, then its subscriptions. */
@Composable
private fun GroupTile(group: SubGroup, currency: String, suggested: Boolean = false, content: @Composable () -> Unit) {
    val colors = GridTheme.colors
    Tile(
        borderColor = if (suggested) colors.accentText.copy(alpha = 0.35f) else colors.hairline,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(group.category.iconKey, group.category.colorKey, size = 26.dp)
            Text(group.category.name, style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.weight(1f).padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            AmountText(group.monthlyMinor, currency, style = GridText.moneyTiny, color = colors.muted)
            Text(stringResource(R.string.bills_per_month_short), style = MaterialTheme.typography.labelSmall, color = colors.muted)
        }
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 48.dp).height(1.dp).background(GridTheme.colors.hairline))
}

/** A tracked subscription inside its category card: name, cadence and next date, amount, this month's status. */
@Composable
private fun SubscriptionLine(sub: Subscription, today: LocalDate, charge: MonthCharge?, onClick: () -> Unit) {
    val colors = GridTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        MonogramBadge(sub.name, sub.colorKey, size = 36.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(sub.name, style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when (sub.status) {
                SubscriptionStatus.ACTIVE, SubscriptionStatus.SUGGESTED -> cycleLabel(sub) + " · " + relativeDay(sub.nextCharge, today)
                SubscriptionStatus.PAUSED -> stringResource(R.string.bills_paused)
                SubscriptionStatus.CANCELLED -> stringResource(R.string.bills_cancelled)
            }
            Text(status + if (sub.amountVaries) " · " + stringResource(R.string.bills_amount_varies) else "", style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sub.amountVaries) Text("≈ ", style = GridText.moneySmall, color = colors.muted)
                AmountText(sub.amountMinor, sub.currency, style = GridText.moneySmall)
            }
            charge?.let { ChargeLabel(it) }
        }
    }
}

/** A bill Gemini found in the payments: added only when the user says so. */
@Composable
private fun SuggestionLine(sub: Subscription, today: LocalDate, onAdd: () -> Unit, onReject: () -> Unit) {
    val colors = GridTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonogramBadge(sub.name, sub.colorKey, size = 36.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(sub.name, style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    cycleLabel(sub) + " · " + stringResource(R.string.bills_next_short, relativeDay(sub.nextCharge, today).replaceFirstChar { it.lowercase() }) +
                        if (sub.amountVaries) " · " + stringResource(R.string.bills_amount_varies) else "",
                    style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sub.amountVaries) Text("≈ ", style = GridText.moneySmall, color = colors.muted)
                AmountText(sub.amountMinor, sub.currency, style = GridText.moneySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onReject) { Text(stringResource(R.string.sub_not_a_bill), color = colors.muted) }
            TextButton(onClick = onAdd) { Text(stringResource(R.string.bills_add_suggested)) }
        }
    }
}

/** ✨ Find subscriptions in my payments: Gemini's pass on demand, with what it found. */
@Composable
private fun FindTile(vm: BillsViewModel) {
    val find by vm.find.collectAsStateWithLifecycle()
    if (!find.available) return
    val colors = GridTheme.colors
    Tile(onClick = if (find.busy) null else ({ vm.findSubscriptions() }), borderColor = colors.accentText.copy(alpha = 0.5f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(if (find.busy) R.string.bills_finding else R.string.bills_find), style = MaterialTheme.typography.titleSmall, color = colors.text)
                val note = when {
                    find.failed -> stringResource(R.string.bills_find_failed)
                    find.found != null -> pluralStringResource(R.plurals.bills_found, find.found!!, find.found!!)
                    else -> stringResource(R.string.bills_find_body)
                }
                Text(note, style = MaterialTheme.typography.bodySmall, color = if (find.failed) colors.danger else colors.muted)
            }
        }
        if (find.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
private fun SuggestionsHeader(count: Int, onAddAll: () -> Unit) {
    val colors = GridTheme.colors
    Column(Modifier.padding(start = 4.dp, top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(16.dp))
            CapsLabel(stringResource(R.string.bills_suggested) + " · $count", Modifier.weight(1f).padding(start = 6.dp), color = colors.accentText)
            if (count > 1) TextButton(onClick = onAddAll) { Text(stringResource(R.string.bills_add_all)) }
        }
        Text(stringResource(R.string.bills_suggested_body), style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

/** A bill Gemini found in the payments: added only when the user says so. */
@Composable
private fun SuggestionRow(sub: Subscription, onAdd: () -> Unit, onReject: () -> Unit) {
    val colors = GridTheme.colors
    Tile(borderColor = colors.accentText.copy(alpha = 0.4f), contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonogramBadge(sub.name, sub.colorKey, size = 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(sub.name, style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    cycleLabel(sub) + if (sub.amountVaries) " · " + stringResource(R.string.bills_amount_varies) else "",
                    style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1,
                )
            }
            AmountText(sub.amountMinor, sub.currency, style = GridText.moneySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onReject) { Text(stringResource(R.string.sub_not_a_bill)) }
            TextButton(onClick = onAdd) { Text(stringResource(R.string.bills_add_suggested)) }
        }
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
                    SubscriptionStatus.SUGGESTED -> cycleLabel(sub)
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
        // "1 Nov"; the year only when it isn't this one.
        date.year == today.year -> date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", com.grid.app.core.designsystem.components.currentLocale()))
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
