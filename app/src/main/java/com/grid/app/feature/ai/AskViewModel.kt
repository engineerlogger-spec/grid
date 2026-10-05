package com.grid.app.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.ai.AgentScreen
import com.grid.app.core.ai.AgentStep
import com.grid.app.core.ai.AiError
import com.grid.app.core.ai.AiKeyStore
import com.grid.app.core.ai.GridAgent
import com.grid.app.core.ai.StepOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** An action in the chat: waiting for the user, or how it ended. */
data class StepUi(val summary: String, val outcome: StepOutcome?)

/** One exchange: the message, the actions taken, and Gemini's reply (null while it works). */
data class AskTurn(
    val question: String,
    val steps: List<StepUi> = emptyList(),
    /** An action waiting for Confirm / Cancel. */
    val pending: AgentStep? = null,
    val answer: String? = null,
    val failed: AiProblem? = null,
)

data class AskUi(val available: Boolean = true, val turns: List<AskTurn> = emptyList()) {
    val thinking: Boolean get() = turns.lastOrNull()?.let { it.answer == null && it.failed == null } == true
}

@HiltViewModel
class AskViewModel @Inject constructor(private val agent: GridAgent, keys: AiKeyStore) : ViewModel() {
    private val _state = MutableStateFlow(AskUi(available = keys.hasKey()))
    val state: StateFlow<AskUi> = _state.asStateFlow()

    private val _screens = Channel<AgentScreen>(Channel.BUFFERED)
    /** Screens the assistant opened, for the chat to navigate to. */
    val screens = _screens.receiveAsFlow()

    private var decision: CompletableDeferred<Boolean>? = null

    fun ask(message: String) {
        val text = message.trim()
        if (text.isEmpty() || _state.value.thinking) return
        _state.update { it.copy(turns = it.turns + AskTurn(text)) }
        viewModelScope.launch {
            try {
                val answer = agent.send(
                    text,
                    confirm = { step -> awaitDecision(step) },
                    onStep = { step, outcome -> updateLast { t -> if (step.needsConfirm) t.copy(steps = t.steps + StepUi(step.summary, outcome)) else t } },
                    onOpenScreen = { _screens.trySend(it) },
                )
                updateLast { it.copy(answer = answer, pending = null) }
            } catch (e: AiError) {
                updateLast {
                    it.copy(
                        pending = null,
                        failed = when (e) {
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

    /** The user's answer to the action waiting in the chat. */
    fun decide(go: Boolean) {
        decision?.complete(go)
    }

    private suspend fun awaitDecision(step: AgentStep): Boolean {
        val d = CompletableDeferred<Boolean>().also { decision = it }
        updateLast { it.copy(pending = step) }
        val go = d.await()
        updateLast { it.copy(pending = null) }
        return go
    }

    private fun updateLast(change: (AskTurn) -> AskTurn) =
        _state.update { s -> s.copy(turns = s.turns.dropLast(1) + change(s.turns.last())) }

    override fun onCleared() {
        decision?.complete(false)
    }
}
