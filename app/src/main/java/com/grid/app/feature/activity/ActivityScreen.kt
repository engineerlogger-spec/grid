package com.grid.app.feature.activity

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.designsystem.components.AmountText
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.CategoryBadge
import com.grid.app.core.designsystem.components.EmptyState
import com.grid.app.core.designsystem.components.GridChip
import com.grid.app.core.designsystem.components.LocalMoneyFormatter
import com.grid.app.core.designsystem.components.MethodBadge
import com.grid.app.core.designsystem.components.MoneyText
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TxType
import com.grid.app.feature.common.LocalMessenger
import com.grid.app.feature.common.LocalQuickAdd
import com.grid.app.feature.common.TransactionRow
import com.grid.app.feature.common.dayLabel
import com.grid.app.feature.common.shortDate

@Composable
fun ActivityScreen(
    contentPadding: PaddingValues,
    onOpenMoved: () -> Unit = {},
    onOpenDeleted: () -> Unit = {},
    onOpenAsk: () -> Unit = {},
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val quickAdd = LocalQuickAdd.current
    val messenger = LocalMessenger.current
    val formatter = LocalMoneyFormatter.current
    val deletedLabel = stringResource(R.string.add_deleted, "%s")
    val undoLabel = stringResource(R.string.action_undo)
    var categorySheet by remember { mutableStateOf(false) }
    var revertedSheet by remember { mutableStateOf<Transaction?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { TopRow(state, viewModel, onOpenDeleted, onOpenAsk) }
        item { SearchField(state.filters.query, viewModel::setQuery) }
        item { FilterRow(state, viewModel, onOpenCategories = { categorySheet = true }) }
        item { TotalsTile(state) }
        if (state.groups.isEmpty() && !state.loading) {
            item {
                EmptyState(
                    icon = if (state.filters.isFiltered) Icons.Rounded.SearchOff else Icons.Rounded.History,
                    title = stringResource(R.string.activity_empty_title),
                    body = stringResource(R.string.activity_empty_body),
                    action = if (state.filters.isFiltered) {
                        { TextButton(onClick = viewModel::clearFilters) { Text(stringResource(R.string.activity_clear_filters)) } }
                    } else null,
                )
            }
        }
        state.groups.forEach { group ->
            item(key = "h${group.date}") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CapsLabel(dayLabel(group.date, state.today), Modifier.weight(1f))
                    if (group.spentMinor > 0) AmountText(-group.spentMinor, state.currency, style = GridText.moneyTiny, color = GridTheme.colors.muted)
                    if (group.incomeMinor > 0) {
                        Spacer(Modifier.width(8.dp))
                        AmountText(group.incomeMinor, state.currency, style = GridText.moneyTiny, color = GridTheme.colors.income, signed = true)
                    }
                }
            }
            // A reverted payment keeps its entry's id: keyed apart so "Count it anyway" never shows the same key twice.
            items(group.items, key = { if (it.reverted) "r${it.id}" else it.id }) { tx ->
                if (tx.reverted) {
                    Tile(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                        TransactionRow(tx, showTime = true, onClick = { revertedSheet = tx })
                    }
                } else if (tx.ownTransfer) {
                    // Money moved between own accounts: shown with its sign, managed on the "Moved to Revolut" screen.
                    Tile(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                        TransactionRow(tx, showTime = false, onClick = onOpenMoved)
                    }
                } else {
                    SwipeToDelete(onDelete = {
                        viewModel.delete(tx) { deleted ->
                            messenger.show(deletedLabel.replace("%s", formatter.format(deleted.amountMinor, deleted.currency)), undoLabel) { viewModel.restore(deleted) }
                        }
                    }) {
                        Tile(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                            TransactionRow(tx, showTime = true, onClick = { quickAdd.edit(tx.id) })
                        }
                    }
                }
            }
        }
    }

    if (categorySheet) {
        CategoryFilterSheet(state, onToggle = viewModel::toggleCategory, onClear = { viewModel.setCategories(emptySet()) }, onDismiss = { categorySheet = false })
    }
    revertedSheet?.let { tx ->
        AlertDialog(
            onDismissRequest = { revertedSheet = null },
            title = { Text(stringResource(R.string.activity_reverted_title)) },
            text = { Text(stringResource(R.string.activity_reverted_body, formatter.format(tx.amountMinor, tx.currency))) },
            confirmButton = { TextButton(onClick = { revertedSheet = null }) { Text(stringResource(R.string.action_done)) } },
            dismissButton = {
                TextButton(onClick = { viewModel.countAnyway(tx); revertedSheet = null }) { Text(stringResource(R.string.activity_reverted_count)) }
            },
            containerColor = GridTheme.colors.tile,
        )
    }
}

@Composable
private fun TopRow(state: ActivityUiState, vm: ActivityViewModel, onOpenDeleted: () -> Unit, onOpenAsk: () -> Unit) {
    val colors = GridTheme.colors
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.activity_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
            Text(
                if (state.filters.allTime) stringResource(R.string.activity_all_time) else state.periodTitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        IconButton(onClick = onOpenAsk) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = stringResource(R.string.ask_title), tint = colors.accentText)
        }
        IconButton(onClick = onOpenDeleted) {
            Icon(Icons.Rounded.RestoreFromTrash, contentDescription = stringResource(R.string.deleted_title), tint = colors.muted)
        }
        IconButton(onClick = vm::previousPeriod) { Icon(Icons.Rounded.ChevronLeft, contentDescription = null, tint = colors.muted) }
        IconButton(onClick = vm::nextPeriod, enabled = state.canGoNext) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = if (state.canGoNext) colors.muted else colors.faint)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val colors = GridTheme.colors
    // The field owns its text: echoing it back through the ViewModel's async state would race with
    // fast typing (dropped / reordered characters). The only external change is "Clear filters".
    var text by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) { if (query.isEmpty()) text = "" }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.activity_search)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.muted) },
        trailingIcon = if (text.isNotEmpty()) {
            { IconButton(onClick = { text = ""; onChange("") }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.activity_clear_search)) } }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = colors.hairline, focusedBorderColor = colors.text.copy(alpha = 0.5f),
            unfocusedContainerColor = colors.tile, focusedContainerColor = colors.tile,
        ),
    )
}

@Composable
private fun FilterRow(state: ActivityUiState, vm: ActivityViewModel, onOpenCategories: () -> Unit) {
    val f = state.filters
    var typeMenu by remember { mutableStateOf(false) }
    var methodMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        f.day?.let { day ->
            GridChip(label = stringResource(R.string.activity_day_filter, shortDate(day)), icon = Icons.Rounded.Close, selected = true, onClick = vm::clearDay)
        }
        Box {
            GridChip(
                label = when (f.type) {
                    null -> stringResource(R.string.activity_all)
                    TxType.EXPENSE -> stringResource(R.string.activity_expenses)
                    TxType.INCOME -> stringResource(R.string.activity_incomes)
                },
                icon = Icons.Rounded.SwapVert,
                selected = f.type != null,
                onClick = { typeMenu = true },
            )
            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                listOf(null, TxType.EXPENSE, TxType.INCOME).forEach { type ->
                    DropdownMenuItem(
                        text = { Text(stringResource(when (type) { null -> R.string.activity_all; TxType.EXPENSE -> R.string.activity_expenses; TxType.INCOME -> R.string.activity_incomes })) },
                        onClick = { vm.setType(type); typeMenu = false },
                    )
                }
            }
        }
        GridChip(
            label = if (f.categoryIds.isEmpty()) stringResource(R.string.activity_all_categories)
            else pluralStringResource(R.plurals.activity_categories_selected, f.categoryIds.size, f.categoryIds.size),
            icon = Icons.Rounded.Category,
            selected = f.categoryIds.isNotEmpty(),
            onClick = onOpenCategories,
        )
        Box {
            GridChip(
                label = if (f.methodIds.isEmpty()) stringResource(R.string.activity_all_methods) else state.methods.filter { it.id in f.methodIds }.joinToString { it.name },
                selected = f.methodIds.isNotEmpty(),
                onClick = { methodMenu = true },
            )
            DropdownMenu(expanded = methodMenu, onDismissRequest = { methodMenu = false }) {
                state.methods.forEach { m ->
                    DropdownMenuItem(
                        leadingIcon = { MethodBadge(m.kind) },
                        text = { Text(m.name) },
                        trailingIcon = if (m.id in f.methodIds) { { Text("✓") } } else null,
                        onClick = { vm.toggleMethod(m.id) },
                    )
                }
            }
        }
        GridChip(label = stringResource(R.string.activity_all_time), icon = Icons.Rounded.History, selected = f.allTime, onClick = vm::toggleAllTime)
        if (f.isFiltered) {
            GridChip(label = stringResource(R.string.activity_clear_filters), icon = Icons.Rounded.FilterAltOff, onClick = vm::clearFilters)
        }
    }
}

@Composable
private fun TotalsTile(state: ActivityUiState) {
    val colors = GridTheme.colors
    Tile {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                CapsLabel(stringResource(R.string.activity_spent))
                MoneyText(state.spentMinor, state.currency, style = GridText.moneyMedium, fractionColor = colors.muted)
            }
            Column(Modifier.weight(1f)) {
                CapsLabel(stringResource(R.string.activity_earned))
                MoneyText(state.incomeMinor, state.currency, style = GridText.moneyMedium, color = colors.income, fractionColor = colors.income.copy(alpha = 0.6f))
            }
            Column(Modifier.weight(1f)) {
                CapsLabel(stringResource(R.string.activity_net))
                MoneyText(state.incomeMinor - state.spentMinor, state.currency, style = GridText.moneyMedium, signed = true, fractionColor = colors.muted)
            }
        }
    }
}

@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val colors = GridTheme.colors
    val dismissState = rememberSwipeToDismissBoxState()
    // The list keeps saved state per entry: an entry restored by Undo would come back still swiped away (invisible).
    LaunchedEffect(Unit) { if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) dismissState.snapTo(SwipeToDismissBoxValue.Settled) }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            val bg by animateColorAsState(if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) colors.danger else Color.Transparent, label = "swipe")
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(bg).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.action_delete), tint = Color.White)
            }
        },
    ) { content() }
}

@Composable
private fun CategoryFilterSheet(state: ActivityUiState, onToggle: (Long) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val colors = GridTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.tile) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.activity_all_categories), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text(stringResource(R.string.activity_clear_filters)) }
            }
            LazyColumn {
                items(state.categories, key = { it.id }) { c ->
                    val selected = c.id in state.filters.categoryIds
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) colors.raised else Color.Transparent)
                            .then(Modifier.padding(0.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onToggle(c.id) }, modifier = Modifier.fillMaxWidth()) {
                            CategoryBadge(c.iconKey, c.colorKey, size = 28.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(c.name, color = colors.text, modifier = Modifier.weight(1f))
                            if (selected) Text("✓", color = colors.accentText)
                        }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_done)) }
        }
    }
}
