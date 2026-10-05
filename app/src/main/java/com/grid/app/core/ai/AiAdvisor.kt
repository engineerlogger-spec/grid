package com.grid.app.core.ai

import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.time.AppClock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** "Ask Grid" and the monthly notes: Gemini reading a snapshot of the user's own money. */
@Singleton
class AiAdvisor @Inject constructor(
    private val gemini: GeminiClient,
    private val keys: AiKeyStore,
    private val snapshots: MoneySnapshots,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun available(): Boolean = keys.hasKey()

    /** Answers [question] (with the conversation so far, oldest first). */
    suspend fun ask(question: String, earlier: List<Pair<String, String>>): String =
        AiPrompts.parseAnswer(gemini.askJson(AiPrompts.ask(snapshots.build().toJson(), question.trim(), earlier.takeLast(MEMORY))))

    /** This month's notes, written once per day at most (and on demand with [refresh]). */
    suspend fun digest(refresh: Boolean = false): List<DigestNote> {
        if (!refresh) cachedDigest()?.let { return it }
        val notes = AiPrompts.parseDigest(gemini.askJson(AiPrompts.digest(snapshots.build().toJson())))
        settings.saveDigest(clock.today().toEpochDay(), json.encodeToString(ListSerializer(DigestNote.serializer()), notes))
        return notes
    }

    /** The notes saved earlier today, without calling Gemini. */
    suspend fun cachedDigest(): List<DigestNote>? {
        val cached = settings.digest() ?: return null
        if (cached.first != clock.today().toEpochDay()) return null
        return runCatching { json.decodeFromString(ListSerializer(DigestNote.serializer()), cached.second) }.getOrNull()
    }

    private companion object {
        /** Earlier questions kept for follow-ups ("and in August?"). */
        const val MEMORY = 4
    }
}
