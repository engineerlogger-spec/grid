package com.grid.app.feature.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.grid.app.core.model.TxType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A request to show the quick-add sheet. [token] makes repeated identical requests distinct. */
data class QuickAddRequest(val type: TxType = TxType.EXPENSE, val editId: Long? = null, val token: Long = System.nanoTime())

/** App-wide handle to open the quick-add sheet from any screen (or a widget/tile/shortcut intent). */
@Stable
class QuickAddController {
    var request by mutableStateOf<QuickAddRequest?>(null)
        private set

    fun add(type: TxType = TxType.EXPENSE) { request = QuickAddRequest(type) }
    fun edit(id: Long) { request = QuickAddRequest(editId = id) }
    fun close() { request = null }
}

/** Snackbar helper so screens can say "Saved · Undo" without owning a host. */
@Stable
class Messenger(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    /** [onAction] runs in the app-wide scope, so an Undo still completes after its screen has closed. */
    fun show(message: String, actionLabel: String? = null, onAction: (suspend () -> Unit)? = null) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            // Undo offers stay up longer: people glance at the amount before deciding.
            val duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
            val result = host.showSnackbar(message, actionLabel, withDismissAction = false, duration = duration)
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

val LocalQuickAdd = staticCompositionLocalOf<QuickAddController> { error("QuickAddController not provided") }
val LocalMessenger = staticCompositionLocalOf<Messenger> { error("Messenger not provided") }
