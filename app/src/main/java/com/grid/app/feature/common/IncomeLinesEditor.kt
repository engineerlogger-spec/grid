package com.grid.app.feature.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.grid.app.R
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.IncomeLine

/** Editable income row used by onboarding and the monthly check-in. Amount is kept as typed text. */
data class IncomeLineDraft(
    val key: Long = System.nanoTime(),
    val name: String,
    val amount: String,
    val categoryId: Long,
    val active: Boolean = true,
) {
    fun amountMinor(currency: String): Long = parseMoney(amount, currency) ?: 0L
    fun toIncomeLine(currency: String) = IncomeLine(name.trim(), amountMinor(currency), categoryId, active)
}

fun List<IncomeLineDraft>.totalMinor(currency: String): Long = filter { it.active }.sumOf { it.amountMinor(currency) }

@Composable
fun IncomeLinesEditor(
    lines: List<IncomeLineDraft>,
    currency: String,
    onChange: (List<IncomeLineDraft>) -> Unit,
    onAdd: () -> Unit,
    showActiveToggle: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = GridTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lines.forEachIndexed { index, line ->
            fun update(transform: (IncomeLineDraft) -> IncomeLineDraft) = onChange(lines.toMutableList().also { it[index] = transform(line) })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = line.name,
                    onValueChange = { v -> update { it.copy(name = v) } },
                    label = { Text(stringResource(R.string.checkin_source_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1.1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = colors.hairline, unfocusedContainerColor = colors.tile, focusedContainerColor = colors.tile),
                )
                MoneyField(
                    value = line.amount,
                    onValueChange = { v -> update { it.copy(amount = v) } },
                    currency = currency,
                    label = stringResource(R.string.checkin_amount),
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Next,
                )
                if (showActiveToggle) {
                    Switch(checked = line.active, onCheckedChange = { v -> update { it.copy(active = v) } })
                } else if (lines.size > 1) {
                    IconButton(onClick = { onChange(lines.toMutableList().also { it.removeAt(index) }) }) {
                        Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = stringResource(R.string.action_delete), tint = colors.muted)
                    }
                }
            }
        }
        TextButton(onClick = onAdd) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Text(stringResource(R.string.action_add_income))
        }
    }
}
