package com.grid.app.core.ai

import com.grid.app.core.model.Cycle
import com.grid.app.core.model.CycleUnit
import com.grid.app.core.money.Currencies
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.util.Locale

/** A payee as Gemini sees it for recognition: its bank name and how it's paid. Never the account holder or an IBAN. */
@Serializable
data class PayeeFacts(val key: String, val raw: String, val kind: String, val payments: Int, val typical: Double)

/** A payee's past payments as [date, amount] pairs, for spotting bills. */
@Serializable
data class PaymentHistory(val key: String, val name: String, val about: String? = null, val payments: List<List<String>>)

data class RecognizedPayee(val key: String, val name: String?, val about: String?, val category: String?, val confidence: Double)

data class FoundBill(
    val key: String,
    val name: String?,
    val cycle: Cycle,
    val amountMinor: Long,
    val varies: Boolean,
    val active: Boolean,
    val confidence: Double,
    /** Gemini's category key for the bill (rent → housing), when it gave one. */
    val category: String? = null,
)

enum class NoteTone { GOOD, WARNING, INFO }

/** One of the monthly notes Gemini writes for Insights. */
@Serializable
data class DigestNote(val title: String, val detail: String, val tone: NoteTone)

/** What Grid asks Gemini, and how its answers are read. Prompts were tuned on real Revolut wording with a cheap model. */
object AiPrompts {
    private val json = Json { encodeDefaults = false }

    fun recognition(payees: List<PayeeFacts>, categories: List<Pair<String, String>>): String = """
        You identify payees on a French bank account (Revolut). Card terminal names are truncated and may carry store
        numbers or bits of a town name; the other payees in the list hint at where the person lives and shops.
        For each payee answer:
        - "name": the business's real, properly written name (keep the given name, nicely capitalised, if you don't know it),
        - "about": what it is, at most 8 words,
        - "category": the key of the best category below,
        - "confidence": 0 to 1, how sure you are which business it is.
        Categories (key: name): ${categories.joinToString { "${it.first}: ${it.second}" }}
        Answer with JSON only: [{"key", "name", "about", "category", "confidence"}], one item per payee, same keys.
        Payees: ${json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PayeeFacts.serializer()), payees)}
    """.trimIndent()

    fun parseRecognition(answer: JsonElement): List<RecognizedPayee> = items(answer).mapNotNull { o ->
        RecognizedPayee(
            key = o.str("key") ?: return@mapNotNull null,
            name = o.str("name")?.trim()?.takeIf { it.isNotBlank() },
            about = o.str("about")?.trim()?.takeIf { it.isNotBlank() },
            category = o.str("category")?.trim()?.lowercase(Locale.ROOT),
            confidence = o.num("confidence") ?: 0.0,
        )
    }

    fun bills(today: LocalDate, currency: String, histories: List<PaymentHistory>, categories: List<Pair<String, String>> = emptyList()): String = """
        Today is $today. Below are payees from a bank account with their past payments ([date, amount in $currency]).
        Find the recurring commitments: subscriptions, phone/internet, energy, water, insurance, rent, loans, gym,
        memberships, regular transfers to the same person (rent, savings).
        Do NOT list everyday shopping (groceries, cafés, restaurants, vending machines, fuel) even when frequent.
        Answer with JSON only: [{"key", "name", "cadence": "weekly"|"monthly"|"quarterly"|"yearly",
        "amount": expected next amount, "varies": true when the amount changes from one payment to the next (phone, energy),
        "active": false when it looks stopped (expected payments are missing), "confidence": 0 to 1,
        "category": the key of the best category for it (rent → housing, phone/energy → bills, streaming → subscriptions…)}]
        Categories (key: name): ${categories.joinToString { "${it.first}: ${it.second}" }}
        Payees: ${json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PaymentHistory.serializer()), histories)}
    """.trimIndent()

    fun parseBills(answer: JsonElement, currency: String): List<FoundBill> = items(answer).mapNotNull { o ->
        val cycle = when (o.str("cadence")?.lowercase(Locale.ROOT)) {
            "weekly" -> Cycle(CycleUnit.WEEK, 1)
            "monthly" -> Cycle.Monthly
            "quarterly" -> Cycle(CycleUnit.MONTH, 3)
            "yearly", "annual", "annually" -> Cycle(CycleUnit.YEAR, 1)
            else -> return@mapNotNull null
        }
        val amount = o.num("amount")?.takeIf { it > 0 } ?: return@mapNotNull null
        FoundBill(
            key = o.str("key") ?: return@mapNotNull null,
            name = o.str("name")?.trim()?.takeIf { it.isNotBlank() },
            cycle = cycle,
            amountMinor = runCatching { Currencies.toMinor(String.format(Locale.ROOT, "%.2f", amount), currency) }.getOrNull() ?: return@mapNotNull null,
            varies = o.bool("varies") ?: false,
            active = o.bool("active") ?: true,
            confidence = o.num("confidence") ?: 0.0,
            category = o.str("category")?.trim()?.lowercase(Locale.ROOT),
        )
    }

    fun ask(snapshotJson: String, question: String, earlier: List<Pair<String, String>>): String = """
        You are Grid, the personal money assistant inside a budgeting app. Answer the person's question using only the
        data below about their own money. Be direct and specific, quote amounts and dates, at most 120 words, plain
        sentences (no markdown, no lists of more than 4 items). If the data can't answer it, say what's missing.
        Payments to their own other accounts are spending; money coming in from them is income.
        Data: $snapshotJson
        ${if (earlier.isEmpty()) "" else "Earlier in this conversation:\n" + earlier.joinToString("\n") { "Q: ${it.first}\nA: ${it.second}" }}
        Question: $question
        Reply with JSON only: {"answer": "..."}
    """.trimIndent()

    fun parseAnswer(answer: JsonElement): String = when (answer) {
        is JsonObject -> answer.str("answer") ?: answer.values.filterIsInstance<JsonPrimitive>().firstOrNull()?.content
        is JsonPrimitive -> answer.content
        else -> null
    }?.trim()?.takeIf { it.isNotBlank() } ?: throw AiError.BadAnswer("No answer")

    fun digest(snapshotJson: String): String = """
        Write this month's notes about the person's money (this month so far, compared with the months before).
        Up to 5 notes, most useful first, chosen from: unusual changes by category, a bill or subscription whose price
        went up (compare its lastPayments), a bill higher than usual, possible double charges, bills not paid yet,
        the balance outlook, and concrete ways to save. Skip anything not supported by the data.
        Each note: "title" (at most 6 words), "detail" (at most 25 words, with amounts), "tone": "good", "warning" or "info".
        Data: $snapshotJson
        Reply with JSON only: [{"title", "detail", "tone"}]
    """.trimIndent()

    fun parseDigest(answer: JsonElement): List<DigestNote> = items(answer).mapNotNull { o ->
        DigestNote(
            title = o.str("title")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
            detail = o.str("detail")?.trim().orEmpty(),
            tone = when (o.str("tone")?.lowercase(Locale.ROOT)) { "good" -> NoteTone.GOOD; "warning" -> NoteTone.WARNING; else -> NoteTone.INFO },
        )
    }.take(5)

    /** The answer is a list of objects, or an object wrapping one (some models add {"payees": [...]}). */
    private fun items(answer: JsonElement): List<JsonObject> = when (answer) {
        is JsonArray -> answer.filterIsInstance<JsonObject>()
        is JsonObject -> answer.values.filterIsInstance<JsonArray>().firstOrNull()?.filterIsInstance<JsonObject>() ?: listOf(answer)
        else -> emptyList()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }
}
