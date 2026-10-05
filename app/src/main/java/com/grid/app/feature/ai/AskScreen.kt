package com.grid.app.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import com.grid.app.core.ai.AgentScreen
import com.grid.app.core.ai.StepOutcome
import com.grid.app.core.designsystem.components.Tile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.designsystem.components.EmptyState
import com.grid.app.core.designsystem.components.GridChip
import com.grid.app.core.designsystem.theme.GridTheme

/** "Ask Grid": questions about your own money, answered by Gemini from a snapshot built on the phone. */
@Composable
fun AskScreen(onBack: () -> Unit, onOpenScreen: (AgentScreen) -> Unit = {}, viewModel: AskViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = GridTheme.colors
    val list = rememberLazyListState()
    val last = state.turns.lastOrNull()
    LaunchedEffect(state.turns.size, state.thinking, last?.pending, last?.steps?.size) { if (state.turns.isNotEmpty()) list.animateScrollToItem(state.turns.size) }
    LaunchedEffect(viewModel) { viewModel.screens.collect(onOpenScreen) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            Text(stringResource(R.string.ask_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                if (!state.available) {
                    EmptyState(Icons.Rounded.AutoAwesome, stringResource(R.string.ask_no_key_title), stringResource(R.string.ask_no_key_body))
                } else if (state.turns.isEmpty()) {
                    EmptyState(Icons.Rounded.AutoAwesome, stringResource(R.string.ask_empty_title), stringResource(R.string.ask_empty_body))
                }
            }
            items(state.turns) { turn ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bubble(turn.question, mine = true)
                    turn.steps.forEach { StepLine(it) }
                    turn.pending?.let { step -> PendingAction(step.summary, onConfirm = { viewModel.decide(true) }, onCancel = { viewModel.decide(false) }) }
                    when {
                        turn.answer != null -> Bubble(turn.answer, mine = false)
                        turn.failed != null -> Bubble(problemText(turn.failed), mine = false, error = true)
                        turn.pending != null -> Unit
                        else -> Bubble(stringResource(R.string.ask_thinking), mine = false, faint = true)
                    }
                }
            }
        }
        if (state.available) {
            if (state.turns.isEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.ask_suggest_food, R.string.ask_suggest_add, R.string.ask_suggest_payee, R.string.ask_suggest_bills, R.string.ask_suggest_afford, R.string.ask_suggest_owe, R.string.ask_suggest_save).forEach { id ->
                        val text = stringResource(id)
                        GridChip(label = text, onClick = { viewModel.ask(text) })
                    }
                }
            }
            AskInput(enabled = !state.thinking, onSend = viewModel::ask)
        }
    }
}

@Composable
private fun AskInput(enabled: Boolean, onSend: (String) -> Unit) {
    // The field owns its text (an echo through the ViewModel would drop keystrokes).
    var text by rememberSaveable { mutableStateOf("") }
    fun send() {
        if (text.isNotBlank() && enabled) {
            onSend(text)
            text = ""
        }
    }
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.ask_hint)) }, maxLines = 4, shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
        )
        IconButton(onClick = { send() }, enabled = enabled && text.isNotBlank()) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.ask_send), tint = GridTheme.colors.accentText)
        }
    }
}

/** A change the assistant wants to make: nothing happens until the user confirms. */
@Composable
private fun PendingAction(summary: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = GridTheme.colors
    Tile(borderColor = colors.accentText.copy(alpha = 0.6f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(18.dp))
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = colors.text, modifier = Modifier.weight(1f).padding(start = 10.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = onConfirm, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.ask_confirm)) }
        }
    }
}

/** What the assistant did (or didn't). */
@Composable
private fun StepLine(step: StepUi) {
    val colors = GridTheme.colors
    val (icon, tint) = when (step.outcome) {
        StepOutcome.DONE -> Icons.Rounded.CheckCircle to colors.income
        StepOutcome.CANCELLED -> Icons.Rounded.Block to colors.muted
        StepOutcome.FAILED, null -> Icons.Rounded.ErrorOutline to colors.danger
    }
    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(step.summary, style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, error: Boolean = false, faint: Boolean = false) {
    val colors = GridTheme.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                error -> colors.danger
                faint -> colors.muted
                mine -> colors.onAccent
                else -> colors.text
            },
            modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp))
                .background(if (mine) colors.accent else colors.tile).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun problemText(problem: AiProblem): String = when (problem) {
    AiProblem.BAD_KEY -> stringResource(R.string.ai_problem_key)
    AiProblem.QUOTA -> stringResource(R.string.ai_problem_quota)
    AiProblem.NETWORK -> stringResource(R.string.ai_problem_network)
    AiProblem.OTHER -> stringResource(R.string.ask_failed)
}
