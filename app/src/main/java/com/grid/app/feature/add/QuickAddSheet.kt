package com.grid.app.feature.add

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.designsystem.components.CategoryBadge
import com.grid.app.core.designsystem.components.GridChip
import com.grid.app.core.designsystem.components.Keypad
import com.grid.app.core.designsystem.components.LocalMoneyFormatter
import com.grid.app.core.designsystem.components.MethodBadge
import com.grid.app.core.designsystem.components.MoneyText
import com.grid.app.core.designsystem.components.Segmented
import com.grid.app.core.designsystem.components.shake
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.Category
import com.grid.app.core.model.TxType
import com.grid.app.core.money.Currencies
import com.grid.app.feature.common.MoneyField
import com.grid.app.feature.common.QuickAddRequest
import com.grid.app.feature.common.shortDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The quick-add bottom sheet. Hosted at the app root so it can open over any screen.
 * [onSaved]/[onDeleted] let the root show an Undo snackbar after the sheet has closed.
 */
@Composable
fun QuickAddSheet(
    request: QuickAddRequest,
    viewModel: QuickAddViewModel,
    onDismiss: () -> Unit,
    onSaved: (QuickAddEvent.Saved) -> Unit,
    onDeleted: (QuickAddEvent.Deleted) -> Unit,
    onMakeSubscription: (transactionId: Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = GridTheme.colors

    LaunchedEffect(request.token) { viewModel.open(request) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            sheetState.hide()
            when (event) {
                is QuickAddEvent.Saved -> onSaved(event)
                is QuickAddEvent.Deleted -> onDeleted(event)
            }
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.tile,
        contentColor = colors.text,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        if (!state.loading) QuickAddContent(state, viewModel, onMakeSubscription)
        else Spacer(Modifier.height(520.dp))
    }
}

@Composable
private fun QuickAddContent(state: QuickAddUiState, vm: QuickAddViewModel, onMakeSubscription: (Long) -> Unit) {
    // The keypad is pinned to the bottom; everything above it scrolls when space runs out (small
    // phones, note field open, keyboard up), so Save is always reachable.
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UpperSection(state, vm, onMakeSubscription)
        }
        if (state.isEditing) {
            // Editing uses the system keyboard for the amount; the calculator keypad is for quick adding.
            Button(onClick = vm::saveSelected, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.action_save))
            }
        } else {
            Keypad(
                onKey = vm::press,
                onSave = vm::saveSelected,
                saveLabel = stringResource(R.string.action_save),
                decimalEnabled = Currencies.fractionDigits(state.currency) > 0,
            )
        }
    }
}

@Composable
private fun ColumnScope.UpperSection(state: QuickAddUiState, vm: QuickAddViewModel, onMakeSubscription: (Long) -> Unit) {
    val colors = GridTheme.colors
    run {
        Surface(Modifier.align(Alignment.CenterHorizontally).size(width = 38.dp, height = 4.dp), shape = RoundedCornerShape(2.dp), color = colors.hairline) {}
        if (state.isEditing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.add_edit_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                // A payment that repeats: make it a subscription, with its earlier payments linked.
                val editing = state.editing
                if (editing != null && editing.type == TxType.EXPENSE && editing.subscriptionId == null) {
                    GridChip(
                        label = stringResource(R.string.add_make_subscription), icon = Icons.Rounded.Autorenew,
                        selected = false, onClick = { onMakeSubscription(editing.id) },
                    )
                }
                IconButton(onClick = vm::delete) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.action_delete), tint = colors.danger)
                }
            }
            state.payeeAbout?.let { about ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(14.dp))
                    Text(
                        stringResource(R.string.add_about_by_gemini, listOfNotNull(state.editing?.merchant, about).joinToString(" · ")),
                        style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        Segmented(
            options = listOf(TxType.EXPENSE, TxType.INCOME),
            selected = state.type,
            label = { stringResource(if (it == TxType.EXPENSE) R.string.add_expense else R.string.add_income) },
            onSelect = vm::setType,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.isEditing) {
            EditableAmount(state, vm::setAmountText)
        } else {
            AmountDisplay(state)
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            DateChip(state.date, state.today, vm::setDate)
            MethodChip(state, vm::setMethod)
            GridChip(
                label = if (state.note.isBlank()) stringResource(R.string.add_note) else state.note,
                icon = Icons.Rounded.EditNote,
                selected = state.noteOpen || state.note.isNotBlank(),
                onClick = vm::toggleNote,
            )
        }

        if (state.noteOpen) {
            val focusManager = LocalFocusManager.current
            OutlinedTextField(
                value = state.note,
                onValueChange = vm::setNote,
                placeholder = { Text(stringResource(R.string.add_note_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = colors.hairline, unfocusedContainerColor = colors.raised, focusedContainerColor = colors.raised),
            )
        }

        if (state.suggestions.isNotEmpty() && state.type == TxType.EXPENSE) {
            val formatter = LocalMoneyFormatter.current
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.suggestions.forEach { s ->
                    GridChip(
                        label = "${s.label} · ${formatter.compact(s.amountMinor, state.currency)}",
                        leading = { CategoryBadge(s.category.iconKey, s.category.colorKey, size = 20.dp) },
                        onClick = { vm.applySuggestion(s) },
                    )
                }
            }
        }

        CategoryGrid(state, vm)

        state.duplicateOf?.let { existing ->
            val formatter = LocalMoneyFormatter.current
            Surface(shape = RoundedCornerShape(14.dp), color = colors.warning.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stringResource(
                            R.string.add_duplicate, existing.title, formatter.format(existing.amountMinor, existing.currency),
                            shortDate(java.time.Instant.ofEpochMilli(existing.occurredAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()),
                        ),
                        style = MaterialTheme.typography.bodyMedium, color = colors.text,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = vm::dismissDuplicate) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(onClick = vm::addAnyway) { Text(stringResource(R.string.add_anyway)) }
                    }
                }
            }
        }

        if (state.asksScope) {
            Text(
                stringResource(R.string.add_scope_title, state.editing?.title.orEmpty()),
                style = MaterialTheme.typography.labelMedium, color = colors.muted,
            )
            Segmented(
                options = listOf(false, true),
                selected = state.applyToAll,
                label = { all -> if (all) stringResource(R.string.add_scope_all, state.similarCount + 1) else stringResource(R.string.add_scope_this) },
                onSelect = vm::setApplyToAll,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            text = when {
                state.error == QuickAddError.ENTER_AMOUNT -> stringResource(R.string.add_enter_amount)
                state.error == QuickAddError.PICK_CATEGORY -> stringResource(R.string.add_pick_category)
                state.selectedCategoryId != null || state.isEditing -> stringResource(R.string.add_hint_selected)
                else -> stringResource(R.string.add_hint_tap_category)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.error != null) colors.danger else colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AmountDisplay(state: QuickAddUiState) {
    val colors = GridTheme.colors
    Column(
        Modifier.fillMaxWidth().shake(if (state.error == QuickAddError.ENTER_AMOUNT) state.shake else 0),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (state.input.isExpression) state.input.display else " ",
            style = GridText.moneySmall,
            color = colors.muted,
            maxLines = 1,
        )
        MoneyText(
            minor = (state.input.valueMinor ?: 0L).coerceAtLeast(0L),
            currency = state.currency,
            style = GridText.moneyHero.copy(fontSize = GridText.moneyHero.fontSize * 1.2f),
            color = if (state.type == TxType.INCOME) colors.income else colors.text,
            fractionColor = colors.muted,
            masked = false,
            animate = false,
        )
    }
}

/**
 * Editing: the amount stays big and pretty (no keypad). Tapping it edits it in place with the system number
 * keyboard; Done (or tapping elsewhere) shows it big again.
 */
@Composable
private fun EditableAmount(state: QuickAddUiState, onChange: (String) -> Unit) {
    val colors = GridTheme.colors
    var typing by remember { mutableStateOf(false) }
    val style = GridText.moneyHero.copy(fontSize = GridText.moneyHero.fontSize * 1.2f, textAlign = TextAlign.Center)
    val tint = if (state.type == TxType.INCOME) colors.income else colors.text
    Column(
        Modifier.fillMaxWidth().shake(if (state.error == QuickAddError.ENTER_AMOUNT) state.shake else 0),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.add_tap_amount), style = GridText.moneySmall, color = colors.muted, maxLines = 1)
        if (!typing) {
            MoneyText(
                minor = (state.amountMinor ?: 0L).coerceAtLeast(0L), currency = state.currency, style = style, color = tint,
                fractionColor = colors.muted, masked = false, animate = false,
                modifier = Modifier.clickable { typing = true },
            )
        } else {
            // The field owns its text while typing (an echo through the ViewModel would drop keystrokes).
            var text by remember { mutableStateOf(state.amountText) }
            val focus = remember { FocusRequester() }
            var focused by remember { mutableStateOf(false) }
            BasicTextField(
                value = text,
                onValueChange = { text = it; onChange(it) },
                textStyle = style.copy(color = tint),
                singleLine = true,
                cursorBrush = SolidColor(colors.accentText),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { typing = false }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged {
                    if (focused && !it.isFocused) typing = false
                    focused = it.isFocused
                },
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }
}

@Composable
private fun DateChip(date: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val label = when (date) {
        today -> stringResource(R.string.add_today)
        today.minusDays(1) -> stringResource(R.string.add_yesterday)
        else -> shortDate(date)
    }
    Box {
        GridChip(label = label, icon = Icons.Rounded.CalendarToday, selected = date != today, onClick = { menu = true })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.add_today)) }, onClick = { onPick(today); menu = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.add_yesterday)) }, onClick = { onPick(today.minusDays(1)); menu = false })
            DropdownMenuItem(text = { Text("…") }, onClick = { menu = false; picker = true })
        }
    }
    if (picker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { picker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picker = false
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = { TextButton(onClick = { picker = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(pickerState) }
    }
}

@Composable
private fun MethodChip(state: QuickAddUiState, onPick: (Long?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val method = state.method
    Box {
        GridChip(
            label = method?.name ?: stringResource(R.string.add_no_method),
            leading = method?.let { { MethodBadge(it.kind, size = 16.dp) } },
            selected = method != null,
            onClick = { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            state.methods.forEach { m ->
                DropdownMenuItem(
                    leadingIcon = { MethodBadge(m.kind) },
                    text = { Text(m.name) },
                    onClick = { onPick(m.id); menu = false },
                )
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.add_no_method)) }, onClick = { onPick(null); menu = false })
        }
    }
}

@Composable
private fun CategoryGrid(state: QuickAddUiState, vm: QuickAddViewModel) {
    val all = state.categories
    val collapsedCount = 7
    val showToggle = all.size > collapsedCount + 1
    val visible = if (state.showAllCategories || !showToggle) all else all.take(collapsedCount)
    val cells: List<Category?> = visible + if (showToggle) listOf(null) else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { category ->
                    if (category == null) {
                        MoreCell(expanded = state.showAllCategories, onClick = vm::toggleShowAll, modifier = Modifier.weight(1f))
                    } else {
                        CategoryCell(
                            category = category,
                            selected = category.id == state.selectedCategoryId,
                            onClick = { vm.tapCategory(category) },
                            onLongClick = { vm.longPressCategory(category) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryCell(category: Category, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
    val colors = GridTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = colors.raised,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) colors.accentText else colors.hairline),
    ) {
        Column(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CategoryBadge(category.iconKey, category.colorKey, size = 32.dp)
            Text(
                category.name,
                style = MaterialTheme.typography.labelSmall,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun MoreCell(expanded: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = GridTheme.colors
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp), color = colors.raised, border = BorderStroke(1.dp, colors.hairline)) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.GridView, contentDescription = null, tint = colors.muted)
            }
            Text(stringResource(if (expanded) R.string.add_less else R.string.add_more), style = MaterialTheme.typography.labelSmall, color = colors.muted)
        }
    }
}
