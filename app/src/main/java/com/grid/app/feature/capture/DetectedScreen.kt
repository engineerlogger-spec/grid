package com.grid.app.feature.capture

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.grid.app.core.designsystem.components.EmptyState
import com.grid.app.core.designsystem.components.GridChip
import com.grid.app.core.designsystem.components.MethodBadge
import com.grid.app.core.designsystem.components.MoneyText
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.data.repo.BankReviewGroup
import com.grid.app.core.data.repo.CaptureItem
import com.grid.app.core.data.repo.ReviewKind
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.CaptureSource
import com.grid.app.core.model.Category
import com.grid.app.core.model.PaymentKind
import com.grid.app.core.money.Currencies
import com.grid.app.feature.common.LocalMessenger
import com.grid.app.feature.common.timeOfDay
import com.grid.app.feature.common.toLocalDate
import com.grid.app.feature.common.shortDate

@Composable
fun DetectedScreen(onBack: () -> Unit, viewModel: DetectedViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = GridTheme.colors
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val addedTemplate = stringResource(R.string.detected_added_snack, "%s")
    var morePickerFor by remember { mutableStateOf<CaptureItem?>(null) }
    var moreForGroup by remember { mutableStateOf<BankReviewGroup?>(null) }

    fun accept(item: CaptureItem, category: Category) {
        viewModel.accept(item, category)
        messenger.show(addedTemplate.replace("%s", category.name))
    }

    fun resolve(group: BankReviewGroup, category: Category) {
        viewModel.resolve(group, category)
        messenger.show(addedTemplate.replace("%s", category.name))
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                Text(stringResource(R.string.detected_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
            }
        }
        if (!state.loading && state.inbox.isEmpty() && state.bankGroups.isEmpty()) {
            item { EmptyState(Icons.Rounded.DoneAll, stringResource(R.string.detected_empty_title), stringResource(R.string.detected_empty_body)) }
        }
        if (state.bankGroups.isNotEmpty()) {
            item { CapsLabel(stringResource(R.string.detected_bank_section), Modifier.padding(start = 4.dp, top = 4.dp)) }
            items(state.bankGroups, key = { "b${it.group.key}" }) { card ->
                BankGroupTile(
                    card,
                    onPick = { resolve(card.group, it) },
                    onMore = { moreForGroup = card.group },
                    onIgnore = { always -> viewModel.ignore(card.group, always) },
                )
            }
            if (state.inbox.isNotEmpty()) {
                item { CapsLabel(stringResource(R.string.detected_notifications_section), Modifier.padding(start = 4.dp, top = 10.dp)) }
            }
        }
        items(state.inbox, key = { "n${it.item.id}" }) { card ->
            DetectedCardTile(card, state.currency, onAccept = { accept(card.item, it) }, onMore = { morePickerFor = card.item }, onDismiss = { viewModel.dismiss(card.item) })
        }
        if (state.recentlyAdded.isNotEmpty()) {
            item { CapsLabel(stringResource(R.string.detected_added_recently), Modifier.padding(start = 4.dp, top = 10.dp)) }
            items(state.recentlyAdded, key = { "a${it.id}" }) { item ->
                Tile(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceBadge(item.source)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(item.merchant ?: sourceName(item.source), style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(whenLabel(item.postedAt), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        }
                        if (item.amountMinor != null && item.currency != null) AmountText(item.amountMinor, item.currency, style = GridText.moneySmall)
                        TextButton(onClick = { viewModel.undo(item) }) { Text(stringResource(R.string.action_undo)) }
                    }
                }
            }
        }
        if (state.unparsed.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CapsLabel(stringResource(R.string.detected_unparsed), Modifier.weight(1f))
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, viewModel.diagnosticsText())
                        context.startActivity(Intent.createChooser(send, null))
                    }) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text(stringResource(R.string.detected_share))
                    }
                    TextButton(onClick = viewModel::clearDiagnostics) { Text(stringResource(R.string.detected_clear)) }
                }
                Text(stringResource(R.string.detected_unparsed_body), style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(horizontal = 4.dp))
            }
            items(state.unparsed, key = { "u${it.id}" }) { item ->
                Tile(contentPadding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceBadge(item.source)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, color = colors.text)
                            Text(item.text, style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        }
                    }
                }
            }
        }
    }

    morePickerFor?.let { item ->
        CategorySheet(viewModel.categoriesFor(item), onPick = { accept(item, it) }, onDismiss = { morePickerFor = null })
    }
    moreForGroup?.let { group ->
        CategorySheet(viewModel.categoriesFor(group), onPick = { resolve(group, it) }, onDismiss = { moreForGroup = null })
    }
}

@Composable
private fun CategorySheet(categories: List<Category>, onPick: (Category) -> Unit, onDismiss: () -> Unit) {
    val colors = GridTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.tile) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            categories.forEach { c ->
                TextButton(onClick = { onPick(c); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    CategoryBadge(c.iconKey, c.colorKey, size = 28.dp)
                    Text(c.name, color = colors.text, modifier = Modifier.weight(1f).padding(start = 12.dp))
                }
            }
        }
    }
}

/** "Lidl · 3 payments · €70.20": one tap on a category settles every payment in the group. */
@Composable
private fun BankGroupTile(card: BankGroupCard, onPick: (Category) -> Unit, onMore: () -> Unit, onIgnore: (always: Boolean) -> Unit) {
    val colors = GridTheme.colors
    val group = card.group
    val incoming = group.kind == ReviewKind.DECIDE_IN
    Tile(borderColor = colors.accentText.copy(alpha = 0.45f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MethodBadge(PaymentKind.REVOLUT, size = 22.dp)
            Text(
                stringResource(
                    when (group.kind) {
                        ReviewKind.CATEGORISE -> R.string.bank_group_categorise
                        ReviewKind.DECIDE_OUT -> R.string.bank_group_out
                        ReviewKind.DECIDE_IN -> R.string.bank_group_in
                    },
                ) + " · " + whenLabel(group.latestAt),
                style = MaterialTheme.typography.labelMedium, color = colors.muted, modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(group.title, style = MaterialTheme.typography.titleLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(R.plurals.bank_group_count, group.count, group.count), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            MoneyText(
                group.totalMinor, group.currency, style = GridText.moneyLarge, signed = incoming,
                color = if (incoming) colors.income else colors.text, fractionColor = colors.muted,
            )
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.suggestions.forEach { c ->
                GridChip(label = c.name, leading = { CategoryBadge(c.iconKey, c.colorKey, size = 20.dp) }, onClick = { onPick(c) })
            }
            GridChip(label = stringResource(R.string.detected_more), onClick = onMore)
        }
        if (group.kind != ReviewKind.CATEGORISE) {
            Row(Modifier.padding(top = 4.dp)) {
                TextButton(onClick = { onIgnore(false) }) { Text(stringResource(R.string.bank_ignore), color = colors.muted) }
                TextButton(onClick = { onIgnore(true) }) { Text(stringResource(R.string.bank_always_ignore), color = colors.muted) }
            }
        }
    }
}

@Composable
private fun DetectedCardTile(card: DetectedCard, appCurrency: String, onAccept: (Category) -> Unit, onMore: () -> Unit, onDismiss: () -> Unit) {
    val colors = GridTheme.colors
    val item = card.item
    Tile(borderColor = colors.accentText.copy(alpha = 0.45f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceBadge(item.source)
            Text(
                "${sourceName(item.source)} · ${whenLabel(item.postedAt)}",
                style = MaterialTheme.typography.labelMedium, color = colors.muted, modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.detected_dismiss), tint = colors.muted) }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                item.merchant ?: sourceName(item.source),
                style = MaterialTheme.typography.titleLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (item.amountMinor != null && item.currency != null) {
                MoneyText(
                    item.amountMinor, item.currency, style = GridText.moneyLarge, signed = item.direction == CaptureDirection.IN,
                    color = if (item.direction == CaptureDirection.IN) colors.income else colors.text, fractionColor = colors.muted,
                )
            }
        }
        if (item.currency != null && item.currency != appCurrency) {
            Text(
                stringResource(R.string.detected_foreign, Currencies.displayName(item.currency)),
                style = MaterialTheme.typography.bodySmall, color = colors.warning, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        // Wraps instead of scrolling sideways: every choice, including "More…", stays visible.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.suggestions.forEach { c ->
                GridChip(label = c.name, leading = { CategoryBadge(c.iconKey, c.colorKey, size = 20.dp) }, onClick = { onAccept(c) })
            }
            GridChip(label = stringResource(R.string.detected_more), onClick = onMore)
        }
    }
}

@Composable
private fun SourceBadge(source: CaptureSource) = MethodBadge(
    when (source) {
        CaptureSource.GOOGLE_WALLET -> PaymentKind.GOOGLE_WALLET
        CaptureSource.PAYPAL -> PaymentKind.PAYPAL
        CaptureSource.REVOLUT -> PaymentKind.REVOLUT
    },
    size = 22.dp,
)

@Composable
fun sourceName(source: CaptureSource): String = stringResource(
    when (source) {
        CaptureSource.GOOGLE_WALLET -> R.string.source_wallet
        CaptureSource.PAYPAL -> R.string.source_paypal
        CaptureSource.REVOLUT -> R.string.source_revolut
    },
)

@Composable
private fun whenLabel(millis: Long): String {
    val date = millis.toLocalDate()
    return if (date == java.time.LocalDate.now()) timeOfDay(millis) else shortDate(date)
}
