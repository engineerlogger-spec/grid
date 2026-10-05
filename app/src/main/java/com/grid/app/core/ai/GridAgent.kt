package com.grid.app.core.ai

import com.grid.app.core.ai.AgentTools.Companion.text
import com.grid.app.core.time.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/** An action the assistant takes, as shown in the chat. */
data class AgentStep(val tool: String, val summary: String, val needsConfirm: Boolean)

enum class StepOutcome { DONE, CANCELLED, FAILED }

/**
 * "Ask Grid" as an assistant that acts: Gemini sees the user's money and the app's functions ([AgentTools]); it calls
 * them, the app runs them — asking the user first for anything that changes data — and Gemini answers in the end.
 * One instance per conversation (it keeps the history, model replies unchanged).
 */
class GridAgent @Inject constructor(
    private val gemini: GeminiClient,
    private val tools: AgentTools,
    private val snapshots: MoneySnapshots,
    private val clock: AppClock,
) {
    private val history = mutableListOf<JsonObject>()

    /**
     * Sends [message] and runs the tool calls that follow. [confirm] is asked for every change (true = go ahead);
     * [onStep] reports each step and how it ended. Returns Gemini's final reply.
     */
    suspend fun send(
        message: String,
        confirm: suspend (AgentStep) -> Boolean,
        onStep: (AgentStep, StepOutcome) -> Unit,
        onOpenScreen: (AgentScreen) -> Unit,
    ): String {
        tools.onOpenScreen = onOpenScreen
        history += userText(message)
        val system = systemPrompt()
        repeat(MAX_ROUNDS) {
            val content = gemini.converse(system, history, tools.declarations())
            history += content
            val parts = (content["parts"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val calls = parts.mapNotNull { it["functionCall"] as? JsonObject }
            if (calls.isEmpty()) {
                return parts.joinToString("") { (it["text"] as? JsonPrimitive)?.content.orEmpty() }.trim().ifBlank { throw AiError.BadAnswer("Empty answer") }
            }
            val responses = calls.map { call -> respond(call, confirm, onStep) }
            history += buildJsonObject { put("role", "user"); put("parts", JsonArray(responses)) }
        }
        throw AiError.BadAnswer("Too many steps")
    }

    private suspend fun respond(call: JsonObject, confirm: suspend (AgentStep) -> Boolean, onStep: (AgentStep, StepOutcome) -> Unit): JsonObject {
        val name = call.text("name").orEmpty()
        val args = call["args"] as? JsonObject ?: JsonObject(emptyMap())
        val tool = tools.find(name)
        val result: JsonObject = if (tool == null) {
            problem("unknown function $name")
        } else {
            val step = AgentStep(name, if (tool.confirm) tool.summary(args) else "", tool.confirm)
            if (tool.confirm && !confirm(step)) {
                onStep(step, StepOutcome.CANCELLED)
                buildJsonObject { put("cancelled", true); put("message", "The user declined. Don't retry unless they ask.") }
            } else {
                try {
                    tool.run(args).also { onStep(step, StepOutcome.DONE) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ToolProblem) {
                    onStep(step, StepOutcome.FAILED)
                    problem(e.message ?: "failed")
                } catch (e: Exception) {
                    onStep(step, StepOutcome.FAILED)
                    problem(e.message ?: e.javaClass.simpleName)
                }
            }
        }
        return buildJsonObject {
            putJsonObject("functionResponse") {
                put("name", name)
                call.text("id")?.let { put("id", it) }
                put("response", result)
            }
        }
    }

    private suspend fun systemPrompt(): String = """
        You are Grid, the assistant inside the user's budgeting app. You can act in the app with the functions:
        use them for any change the user asks for, and use search_transactions to find entries (ids) before editing or
        deleting — never invent ids. The app asks the user to confirm every change, so just call the function.
        Bank payments arrive by themselves from Revolut; subscriptions never add entries. Payments to the user's own other
        accounts are spending; money coming in from them is income.
        After acting, or to answer, reply in at most 3 short sentences of plain text (no markdown), with amounts.
        Today is ${clock.today()}. ${tools.categoryList()}
        The user's money right now: ${snapshots.build().toJson()}
    """.trimIndent()

    private fun userText(text: String) = buildJsonObject {
        put("role", "user")
        put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
    }

    private fun problem(message: String) = buildJsonObject { put("error", message) }

    private companion object {
        /** Gemini ↔ app round trips per message (search, then act, then answer is 3). */
        const val MAX_ROUNDS = 6
    }
}
