package com.grid.app.feature.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grid.app.core.designsystem.components.AmountText
import com.grid.app.core.designsystem.components.CategoryBadge
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TxType

/** One transaction line: category badge, title, "Category · Method", signed amount. */
@Composable
fun TransactionRow(
    tx: Transaction,
    modifier: Modifier = Modifier,
    showTime: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = GridTheme.colors
    val subtitle = buildList {
        if (tx.title != tx.category.name) add(tx.category.name)
        tx.method?.let { add(it.name) }
        if (showTime) add(timeOfDay(tx.occurredAt))
    }.joinToString(" · ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(tx.category.iconKey, tx.category.colorKey)
        Column(Modifier.weight(1f)) {
            Text(tx.title, style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        AmountText(
            minor = tx.signedMinor,
            currency = tx.currency,
            signed = tx.type == TxType.INCOME,
            color = if (tx.type == TxType.INCOME) colors.income else colors.text,
        )
    }
}
