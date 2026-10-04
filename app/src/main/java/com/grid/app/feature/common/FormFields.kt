package com.grid.app.feature.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.grid.app.R
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.CategoryBadge
import com.grid.app.core.designsystem.components.GridChip
import com.grid.app.core.designsystem.components.MethodBadge
import com.grid.app.core.designsystem.theme.CategoryHues
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.Category
import com.grid.app.core.model.PaymentMethod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun FieldLabel(text: String) = CapsLabel(text, Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp))

@Composable
fun GridTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    singleLine: Boolean = true,
) {
    val colors = GridTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        isError = isError,
        shape = RoundedCornerShape(14.dp),
        // "Next" moves focus to the following field, so forms can be filled without reaching for the screen.
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = if (singleLine) androidx.compose.ui.text.input.ImeAction.Next else androidx.compose.ui.text.input.ImeAction.Default,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = colors.hairline, focusedBorderColor = colors.text.copy(alpha = 0.6f),
            unfocusedContainerColor = colors.tile, focusedContainerColor = colors.tile,
        ),
    )
}

/** A tappable field-looking row that opens a picker. */
@Composable
fun PickerField(text: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val colors = GridTheme.colors
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.tile, border = BorderStroke(1.dp, colors.hairline)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
            Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.text, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.muted)
        }
    }
}

@Composable
fun DateField(date: LocalDate?, onPick: (LocalDate?) -> Unit, emptyLabel: String, allowClear: Boolean = false, minDate: LocalDate? = null) {
    var open by remember { mutableStateOf(false) }
    PickerField(
        text = date?.let { shortDate(it) } ?: emptyLabel,
        leading = { Icon(Icons.Rounded.CalendarToday, contentDescription = null, tint = GridTheme.colors.muted, modifier = Modifier.size(20.dp)) },
        onClick = { open = true },
    )
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    minDate == null || !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(minDate)
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                Row {
                    if (allowClear) TextButton(onClick = { onPick(null); open = false }) { Text(stringResource(R.string.pending_no_due)) }
                    TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        ) { DatePicker(state) }
    }
}

@Composable
fun CategoryDropdown(categories: List<Category>, selectedId: Long?, onPick: (Long?) -> Unit, noneLabel: String? = null) {
    var open by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.id == selectedId }
    Box {
        PickerField(
            text = selected?.name ?: noneLabel.orEmpty(),
            leading = selected?.let { { CategoryBadge(it.iconKey, it.colorKey, size = 26.dp) } },
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (noneLabel != null) DropdownMenuItem(text = { Text(noneLabel) }, onClick = { onPick(null); open = false })
            categories.forEach { c ->
                DropdownMenuItem(
                    leadingIcon = { CategoryBadge(c.iconKey, c.colorKey, size = 26.dp) },
                    text = { Text(c.name) },
                    onClick = { onPick(c.id); open = false },
                )
            }
        }
    }
}

@Composable
fun MethodDropdown(methods: List<PaymentMethod>, selectedId: Long?, onPick: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = methods.firstOrNull { it.id == selectedId }
    Box {
        PickerField(
            text = selected?.name ?: stringResource(R.string.add_no_method),
            leading = selected?.let { { MethodBadge(it.kind) } },
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            methods.forEach { m ->
                DropdownMenuItem(leadingIcon = { MethodBadge(m.kind) }, text = { Text(m.name) }, onClick = { onPick(m.id); open = false })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.add_no_method)) }, onClick = { onPick(null); open = false })
        }
    }
}

/** Reminder lead time: Off / On the day / 1 day / 3 days / 1 week. */
@Composable
fun ReminderChips(days: Int?, onPick: (Int?) -> Unit) {
    val options = listOf<Pair<Int?, String>>(
        null to stringResource(R.string.remind_off),
        0 to stringResource(R.string.remind_same_day),
        1 to stringResource(R.string.remind_one_day),
        3 to pluralStringResource(R.plurals.remind_days_before, 3, 3),
        7 to stringResource(R.string.remind_week),
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) -> GridChip(label = label, selected = days == value, onClick = { onPick(value) }) }
    }
}

@Composable
fun ColorPicker(selected: String, onPick: (String) -> Unit) {
    val colors = GridTheme.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CategoryHues.keys.forEach { key ->
            val c = colors.category(key)
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(c.fg)
                    .then(if (key == selected) Modifier.border(3.dp, colors.text, CircleShape) else Modifier)
                    .clickable { onPick(key) },
                contentAlignment = Alignment.Center,
            ) {
                if (key == selected) Icon(Icons.Rounded.Check, contentDescription = key, tint = colors.background, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Vertical stack of labelled form sections with consistent spacing. */
@Composable
fun FormSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        content()
    }
}
