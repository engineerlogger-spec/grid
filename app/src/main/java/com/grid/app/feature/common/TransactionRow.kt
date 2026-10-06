package com.grid.app.feature.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grid.app.R
import com.grid.app.core.bank.BankTime
import com.grid.app.core.designsystem.components.AmountText
import com.grid.app.core.designsystem.components.CategoryBadge
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import java.time.ZoneId

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
        if (tx.reverted) add(stringResource(R.string.activity_reverted))
        if (tx.pending) add(stringResource(R.string.activity_pending))
        if (tx.title != tx.category.name) add(tx.category.name)
        tx.method?.let { add(it.name) }
        // A bank that gives only the day: no time to show (Revolut's payments carry their real time).
        if (showTime && !(tx.source == TxSource.BANK && BankTime.isDayOnly(tx.occurredAt, ZoneId.systemDefault()))) add(timeOfDay(tx.occurredAt))
    }.joinToString(" · ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A reverted payment is listed but counted nowhere: faded, its amount struck through.
        Box(Modifier.alpha(if (tx.reverted) 0.5f else 1f)) { CategoryBadge(tx.category.iconKey, tx.category.colorKey) }
        Column(Modifier.weight(1f)) {
            Text(tx.title, style = MaterialTheme.typography.titleSmall, color = if (tx.reverted) colors.muted else colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        AmountText(
            minor = tx.signedMinor,
            currency = tx.currency,
            style = if (tx.reverted) GridText.moneySmall.copy(textDecoration = TextDecoration.LineThrough) else GridText.moneySmall,
            signed = tx.type == TxType.INCOME,
            color = when {
                tx.reverted -> colors.muted
                tx.type == TxType.INCOME -> colors.income
                else -> colors.text
            },
        )
    }
}
