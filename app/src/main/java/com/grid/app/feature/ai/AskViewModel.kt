package com.grid.app.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.ai.AiAdvisor
import com.grid.app.core.ai.AiError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One exchange; [answer] is null while Gemini thinks, [failed] when it couldn't answer. */
data class AskTurn(val question: String, val answer: String? = null, val failed: AiProblem? = null)

data class AskUi(val available: Boolean = true, val turns: List<AskTurn> = emptyList()) {
    val thinking: Boolean get() = turns.lastOrNull()?.let { it.answer == null && it.failed == null } == true
}

@HiltViewModel
class AskViewModel @Inject constructor(private val advisor: AiAdvisor) : ViewModel() {
    private val _state = MutableStateFlow(AskUi(available = advisor.available()))
    val state: StateFlow<AskUi> = _state.asStateFlow()

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _state.value.thinking) return
        val earlier = _state.value.turns.mapNotNull { t -> t.answer?.let { t.question to it } }
        _state.update { it.copy(turns = it.turns + AskTurn(q)) }
        viewModelScope.launch {
            val turn = try {
                AskTurn(q, answer = advisor.ask(q, earlier))
            } catch (e: AiError) {
                AskTurn(
                    q,
                    failed = when (e) {
                        is AiError.BadKey, is AiError.NoKey -> AiProblem.BAD_KEY
                        is AiError.Quota -> AiProblem.QUOTA
                        is AiError.Network -> AiProblem.NETWORK
                        else -> AiProblem.OTHER
                    },
                )
            }
            _state.update { it.copy(turns = it.turns.dropLast(1) + turn) }
        }
    }
}
