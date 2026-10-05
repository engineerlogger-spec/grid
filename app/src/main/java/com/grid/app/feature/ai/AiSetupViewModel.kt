package com.grid.app.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.ai.AiAssistant
import com.grid.app.core.ai.AiError
import com.grid.app.core.ai.AiKeyStore
import com.grid.app.core.ai.AiRunResult
import com.grid.app.core.data.prefs.AiStatus
import com.grid.app.core.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What went wrong, for the screen to word it. */
enum class AiProblem { BAD_KEY, QUOTA, NETWORK, OTHER }

data class AiSetupUi(
    val hasKey: Boolean = false,
    val busy: Boolean = false,
    val status: AiStatus = AiStatus(),
    val problem: AiProblem? = null,
    val problemDetail: String? = null,
    /** Just finished: how many payees and bills. */
    val done: AiRunResult.Ok? = null,
)

@HiltViewModel
class AiSetupViewModel @Inject constructor(
    private val keys: AiKeyStore,
    private val assistant: AiAssistant,
    settings: SettingsRepository,
) : ViewModel() {

    private val local = MutableStateFlow(AiSetupUi(hasKey = keys.hasKey()))

    val state: StateFlow<AiSetupUi> = combine(local, settings.settings.map { it.ai }) { l, status -> l.copy(status = status) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSetupUi(hasKey = keys.hasKey()))

    /** Saves the key, checks it with one tiny request, then runs the first analysis of the whole history. */
    fun saveKey(raw: String) = viewModelScope.launch {
        val key = raw.trim().takeIf { it.length >= MIN_KEY_LENGTH } ?: run {
            local.value = local.value.copy(problem = AiProblem.BAD_KEY, problemDetail = null)
            return@launch
        }
        local.value = local.value.copy(busy = true, problem = null, done = null)
        withContext(Dispatchers.IO) { keys.save(key) }
        val error = assistant.test()
        if (error is AiError.BadKey) {
            withContext(Dispatchers.IO) { keys.clear() }
            local.value = local.value.copy(busy = false, hasKey = false, problem = AiProblem.BAD_KEY, problemDetail = error.message)
            return@launch
        }
        local.value = local.value.copy(hasKey = true)
        analyse(alreadyBusy = true)
    }

    fun analyse(alreadyBusy: Boolean = false) = viewModelScope.launch {
        if (!alreadyBusy) local.value = local.value.copy(busy = true, problem = null, done = null)
        val result = assistant.run()
        local.value = when (result) {
            is AiRunResult.Ok -> local.value.copy(busy = false, done = result)
            is AiRunResult.Failed -> local.value.copy(busy = false, problem = result.error.toProblem(), problemDetail = result.error.message)
            AiRunResult.NoKey -> local.value.copy(busy = false, hasKey = false)
        }
    }

    fun removeKey() = viewModelScope.launch {
        withContext(Dispatchers.IO) { keys.clear() }
        local.value = AiSetupUi(hasKey = false)
    }

    private fun AiError.toProblem() = when (this) {
        is AiError.BadKey, is AiError.NoKey -> AiProblem.BAD_KEY
        is AiError.Quota -> AiProblem.QUOTA
        is AiError.Network -> AiProblem.NETWORK
        else -> AiProblem.OTHER
    }

    private companion object {
        const val MIN_KEY_LENGTH = 20
    }
}
