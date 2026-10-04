package com.grid.app.core.data.repo

/**
 * Called after any committed change to money data (transactions, plans). Implementations refresh
 * dependants that live outside the UI's Flows: the home-screen widget, budget alerts, etc.
 * Bound into a Hilt multibinding set; failures are isolated so one listener can't break a save.
 */
fun interface LedgerListener {
    suspend fun onLedgerChanged()
}

internal suspend fun Set<LedgerListener>.notifyAll() {
    forEach { listener -> runCatching { listener.onLedgerChanged() } }
}
