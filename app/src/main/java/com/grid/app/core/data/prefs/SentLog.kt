package com.grid.app.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Remembers which one-shot notifications (reminders, alerts, check-in nudges) were already posted,
 * so a re-run never repeats them. Entries are stored as `key@epochDay` and pruned after [RETENTION_DAYS].
 */
class SentLog(private val store: DataStore<Preferences>) {
    private val entriesKey = stringSetPreferencesKey("sent")

    suspend fun sentKeys(): Set<String> = store.data.first()[entriesKey].orEmpty().map { it.substringBeforeLast('@') }.toSet()

    suspend fun markSent(keys: Collection<String>, today: LocalDate) {
        if (keys.isEmpty()) return
        val cutoff = today.toEpochDay() - RETENTION_DAYS
        store.edit { prefs ->
            val kept = prefs[entriesKey].orEmpty().filter { (it.substringAfterLast('@').toLongOrNull() ?: 0) >= cutoff }
            prefs[entriesKey] = (kept + keys.map { "$it@${today.toEpochDay()}" }).toSet()
        }
    }

    companion object {
        const val RETENTION_DAYS = 60
    }
}
