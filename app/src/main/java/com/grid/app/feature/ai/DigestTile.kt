package com.grid.app.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.grid.app.R
import com.grid.app.core.ai.AiAdvisor
import com.grid.app.core.ai.AiError
import com.grid.app.core.ai.DigestNote
import com.grid.app.core.ai.NoteTone
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.theme.GridTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DigestUi(val available: Boolean = false, val busy: Boolean = false, val notes: List<DigestNote>? = null, val problem: AiProblem? = null)

@HiltViewModel
class DigestViewModel @Inject constructor(private val advisor: AiAdvisor) : ViewModel() {
    private val _state = MutableStateFlow(DigestUi(available = advisor.available()))
    val state: StateFlow<DigestUi> = _state.asStateFlow()

    init {
        // Today's notes are shown straight away; Gemini is only asked when the user taps.
        viewModelScope.launch { advisor.cachedDigest()?.let { notes -> _state.update { it.copy(notes = notes) } } }
    }

    fun generate(refresh: Boolean) = viewModelScope.launch {
        _state.update { it.copy(busy = true, problem = null) }
        _state.update {
            try {
                it.copy(busy = false, notes = advisor.digest(refresh))
            } catch (e: AiError) {
                it.copy(
                    busy = false,
                    problem = when (e) {
                        is AiError.BadKey, is AiError.NoKey -> AiProblem.BAD_KEY
                        is AiError.Quota -> AiProblem.QUOTA
                        is AiError.Network -> AiProblem.NETWORK
                        else -> AiProblem.OTHER
                    },
                )
            }
        }
    }
}

/** Insights' "This month, by Gemini": a few plain notes (price rises, unusual spending, double charges, savings). */
@Composable
fun DigestTile(viewModel: DigestViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.available) return
    val colors = GridTheme.colors
    Tile {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(18.dp))
            CapsLabel(stringResource(R.string.digest_title), Modifier.weight(1f).padding(start = 8.dp), color = colors.accentText)
            if (state.notes != null && !state.busy) TextButton(onClick = { viewModel.generate(refresh = true) }) { Text(stringResource(R.string.digest_refresh)) }
        }
        when {
            state.busy -> {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.digest_writing), style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(top = 6.dp))
            }
            state.notes != null -> Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.notes!!.forEach { note -> Note(note) }
            }
            else -> TextButton(onClick = { viewModel.generate(refresh = false) }) { Text(stringResource(R.string.digest_generate)) }
        }
        state.problem?.let { problem ->
            Text(
                when (problem) {
                    AiProblem.BAD_KEY -> stringResource(R.string.ai_problem_key)
                    AiProblem.QUOTA -> stringResource(R.string.ai_problem_quota)
                    AiProblem.NETWORK -> stringResource(R.string.ai_problem_network)
                    AiProblem.OTHER -> stringResource(R.string.ask_failed)
                },
                style = MaterialTheme.typography.bodySmall, color = colors.danger, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Note(note: DigestNote) {
    val colors = GridTheme.colors
    val (icon, tint) = when (note.tone) {
        NoteTone.GOOD -> Icons.Rounded.CheckCircle to colors.income
        NoteTone.WARNING -> Icons.Rounded.Warning to colors.warning
        NoteTone.INFO -> Icons.Rounded.Info to colors.muted
    }
    Row {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Column(Modifier.padding(start = 10.dp)) {
            Text(note.title, style = MaterialTheme.typography.titleSmall, color = colors.text)
            if (note.detail.isNotBlank()) Text(note.detail, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
    }
}
