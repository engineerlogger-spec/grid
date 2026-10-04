package com.grid.app.feature.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.GridChip
import com.grid.app.core.designsystem.components.LocalMoneyFormatter
import com.grid.app.core.designsystem.components.MonthGridIllustration
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.feature.common.IncomeLinesEditor
import com.grid.app.feature.common.MoneyField

@Composable
fun CheckInScreen(onDone: () -> Unit, onLater: () -> Unit, viewModel: CheckInViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = GridTheme.colors
    val formatter = LocalMoneyFormatter.current
    LaunchedEffect(state.done) { if (state.done) onDone() }
    if (state.loading) return

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MonthGridIllustration(Modifier.padding(end = 140.dp))
        CapsLabel(stringResource(R.string.checkin_eyebrow, state.periodTitle), color = colors.accentText)
        Text(stringResource(R.string.checkin_title), style = MaterialTheme.typography.displaySmall, color = colors.text)
        Text(stringResource(R.string.checkin_body), style = MaterialTheme.typography.bodyLarge, color = colors.muted)

        Tile {
            CapsLabel(stringResource(R.string.checkin_income))
            Spacer(Modifier.height(10.dp))
            IncomeLinesEditor(state.lines, state.currency, viewModel::setLines, viewModel::addLine, showActiveToggle = true)
        }

        Tile {
            CapsLabel(stringResource(R.string.checkin_goal))
            Spacer(Modifier.height(10.dp))
            MoneyField(state.goalText, viewModel::setGoal, state.currency, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(70, 80, 90).forEach { pct ->
                    val active = state.incomeMinor > 0 && state.goalMinor == state.incomeMinor * pct / 100
                    GridChip(label = "$pct%", selected = active, onClick = { viewModel.setGoalPercent(pct) })
                }
            }
            val goal = state.goalMinor
            if (goal != null && state.incomeMinor > 0) {
                Text(
                    stringResource(
                        R.string.checkin_goal_hint,
                        (goal * 100 / state.incomeMinor).toInt(),
                        formatter.format((state.incomeMinor - goal).coerceAtLeast(0), state.currency),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Button(
            onClick = viewModel::confirm,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(stringResource(R.string.checkin_confirm, formatter.format(state.incomeMinor, state.currency)), style = MaterialTheme.typography.titleSmall)
        }
        TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_later)) }
    }
}
