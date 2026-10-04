package com.grid.app.feature.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.grid.app.R
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.money.Currencies
import java.util.Locale

/** Searchable currency list; the locale's currency and the current choice are pinned to the top. */
@Composable
fun CurrencyPicker(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = GridTheme.colors
    var query by remember { mutableStateOf("") }
    val pinned = remember(selected) { listOf(selected, Currencies.defaultFor(Locale.getDefault()), "EUR", "USD", "GBP").distinct() }
    val all = remember { Currencies.all() }
    val codes = remember(query, pinned) {
        val q = query.trim().lowercase()
        val ordered = pinned + all.filterNot { it in pinned }
        if (q.isEmpty()) ordered else ordered.filter { it.lowercase().contains(q) || Currencies.displayName(it).lowercase().contains(q) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.onb_currency_search)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.muted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = colors.hairline, unfocusedContainerColor = colors.tile, focusedContainerColor = colors.tile),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(codes, key = { it }) { code ->
                val isSelected = code == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) colors.raised else Color.Transparent)
                        .clickable { onSelect(code) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(code, style = GridText.moneySmall, color = colors.text, modifier = Modifier.width(56.dp))
                    Text(Currencies.displayName(code), style = MaterialTheme.typography.bodyMedium, color = colors.muted, modifier = Modifier.weight(1f))
                    Text(Currencies.symbol(code), style = GridText.moneySmall, color = colors.muted)
                    if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.accentText, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
