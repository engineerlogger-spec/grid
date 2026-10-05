package com.grid.app.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.feature.common.shortDate
import com.grid.app.feature.common.timeOfDay
import com.grid.app.feature.common.toLocalDate
import java.time.LocalDate

/** Settings › AI: the user's free Gemini key, what Gemini does with it, and the last pass over payees and bills. */
@Composable
fun AiSetupScreen(onBack: () -> Unit, onOpenAsk: () -> Unit, viewModel: AiSetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = GridTheme.colors

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        Tile {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(22.dp))
                Text(stringResource(R.string.ai_intro_title), style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.padding(start = 10.dp))
            }
            Spacer(Modifier.height(8.dp))
            listOf(R.string.ai_does_names, R.string.ai_does_bills, R.string.ai_does_ask).forEach {
                Text("•  " + stringResource(it), style = MaterialTheme.typography.bodyMedium, color = colors.text, modifier = Modifier.padding(vertical = 2.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.ai_privacy), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }

        if (!state.hasKey) {
            KeyEntry(state, onSave = viewModel::saveKey)
        } else {
            Tile {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.income, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.ai_key_saved), style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.weight(1f).padding(start = 10.dp))
                    TextButton(onClick = viewModel::removeKey) { Text(stringResource(R.string.ai_remove_key)) }
                }
                Spacer(Modifier.height(6.dp))
                val s = state.status
                val last = s.lastRunAt?.let { at ->
                    val date = at.toLocalDate()
                    if (date == LocalDate.now()) timeOfDay(at) else shortDate(date)
                }
                Text(
                    if (last == null) stringResource(R.string.ai_never_run) else stringResource(R.string.ai_status, last, s.payees, s.bills),
                    style = MaterialTheme.typography.bodySmall, color = colors.muted,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.analyse() }, enabled = !state.busy, shape = RoundedCornerShape(14.dp)) { Text(stringResource(R.string.ai_analyse)) }
                    OutlinedButton(onClick = onOpenAsk, shape = RoundedCornerShape(14.dp)) { Text(stringResource(R.string.ask_title)) }
                }
            }
        }

        state.done?.let { done ->
            Text(stringResource(R.string.ai_done, done.payees, done.bills), style = MaterialTheme.typography.bodyMedium, color = colors.income, modifier = Modifier.padding(horizontal = 4.dp))
        }
        state.problem?.let { problem ->
            Text(
                when (problem) {
                    AiProblem.BAD_KEY -> stringResource(R.string.ai_problem_key)
                    AiProblem.QUOTA -> stringResource(R.string.ai_problem_quota)
                    AiProblem.NETWORK -> stringResource(R.string.ai_problem_network)
                    AiProblem.OTHER -> stringResource(R.string.ai_problem_other, state.problemDetail.orEmpty())
                },
                style = MaterialTheme.typography.bodyMedium, color = colors.danger, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        state.status.lastError?.takeIf { state.problem == null && state.hasKey }?.let {
            Text(stringResource(R.string.ai_problem_other, it), style = MaterialTheme.typography.bodySmall, color = colors.warning, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun KeyEntry(state: AiSetupUi, onSave: (String) -> Unit) {
    val colors = GridTheme.colors
    // The field owns its text (an echo through the ViewModel would drop keystrokes).
    var key by rememberSaveable { mutableStateOf("") }
    Tile {
        CapsLabel(stringResource(R.string.ai_key_label))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.ai_key_how), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(stringResource(R.string.ai_key_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = { onSave(key) }, enabled = key.isNotBlank() && !state.busy, shape = RoundedCornerShape(14.dp)) { Text(stringResource(R.string.ai_save_key)) }
    }
}
