package com.grid.app.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.designsystem.components.AmountText
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.EmptyState
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.TxType
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.feature.common.TransactionRow
import com.grid.app.feature.common.shortDate
import com.grid.app.feature.common.toLocalDate

/** Entries deleted in the last 30 days (and bank payments deleted before that existed), each restorable in one tap. */
@Composable
fun RecentlyDeletedScreen(onBack: () -> Unit, viewModel: RecentlyDeletedViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = GridTheme.colors

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                Text(stringResource(R.string.deleted_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
            }
        }
        item {
            Text(
                stringResource(R.string.deleted_body, TransactionRepository.TRASH_DAYS),
                style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (state.isEmpty && !state.loading) {
            item { EmptyState(Icons.Rounded.RestoreFromTrash, stringResource(R.string.deleted_empty_title), stringResource(R.string.deleted_empty_body)) }
        }
        items(state.deleted, key = { "d${it.tx.id}" }) { entry ->
            Tile(contentPadding = PaddingValues(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        TransactionRow(entry.tx)
                        Text(
                            stringResource(R.string.deleted_on, shortDate(entry.tx.occurredAt.toLocalDate()), shortDate(entry.deletedAt.toLocalDate())),
                            style = MaterialTheme.typography.labelSmall, color = colors.muted, modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    TextButton(onClick = { viewModel.restore(entry) }) { Text(stringResource(R.string.deleted_restore)) }
                }
            }
        }
        if (state.earlier.isNotEmpty()) {
            item { CapsLabel(stringResource(R.string.deleted_earlier), Modifier.padding(start = 4.dp, top = 8.dp)) }
            items(state.earlier, key = { "e${it.rowId}" }) { payment ->
                Tile(contentPadding = PaddingValues(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(payment.title, style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(shortDate(payment.date), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        }
                        AmountText(
                            if (payment.type == TxType.EXPENSE) -payment.amountMinor else payment.amountMinor, payment.currency,
                            style = GridText.moneySmall, signed = true, color = if (payment.type == TxType.INCOME) colors.income else colors.text,
                        )
                        TextButton(onClick = { viewModel.restore(payment) }) { Text(stringResource(R.string.deleted_restore)) }
                    }
                }
            }
        }
    }
}
