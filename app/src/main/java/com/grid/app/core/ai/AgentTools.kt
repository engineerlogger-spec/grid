package com.grid.app.core.ai

import android.content.Context
import com.grid.app.core.bank.BankSyncWorker
import com.grid.app.core.data.prefs.LowFundsMode
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.PendingRepository
import com.grid.app.core.data.repo.PlanRepository
import com.grid.app.core.data.repo.SubscriptionRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.Cycle
import com.grid.app.core.model.CycleUnit
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.PendingDirection
import com.grid.app.core.model.PendingDraft
import com.grid.app.core.model.SubscriptionDraft
import com.grid.app.core.model.SubscriptionStatus
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TransactionDraft
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.money.Currencies
import com.grid.app.core.money.MoneyFormatter
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BillingSchedule
import com.grid.app.core.time.BudgetPeriods
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A screen the assistant can open. */
enum class AgentScreen { HOME, ACTIVITY, BILLS, INSIGHTS, SAVINGS, SETTINGS, AI, RECENTLY_DELETED, BANK }

/**
 * One app function Gemini may call. [confirm]: it changes the user's data, so the app asks first (with [summary]).
 * [run] returns what Gemini is told back (a result or an "error" to explain).
 */
class AgentTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val confirm: Boolean,
    val summary: suspend (JsonObject) -> String,
    val run: suspend (JsonObject) -> JsonObject,
)

/** A tool asked for something it can't do (unknown entry, category…): Gemini gets the message to explain or retry. */
class ToolProblem(message: String) : Exception(message)

/** Everything the assistant can do in Grid, backed by the same repositories as the screens. */
@Singleton
class AgentTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val plans: PlanRepository,
    private val subscriptions: SubscriptionRepository,
    private val pendings: PendingRepository,
    private val settings: SettingsRepository,
    private val formatter: MoneyFormatter,
    private val clock: AppClock,
) {
    /** Screens opened by [open_screen] reach the UI through this callback (set by the chat). */
    var onOpenScreen: (AgentScreen) -> Unit = {}

    val all: List<AgentTool> by lazy {
        listOf(
            searchTransactions(), addTransaction(), editTransaction(), deleteTransaction(), setPayeeCategory(),
            setSalary(), setMonthlyBudget(), setCategoryBudget(), addSubscription(), removeSubscription(), addPending(),
            setLowFundsWarning(), syncBank(), analysePayments(), openScreen(),
        )
    }

    fun declarations(): JsonArray = buildJsonArray {
        all.forEach { t -> add(buildJsonObject { put("name", t.name); put("description", t.description); put("parameters", t.parameters) }) }
    }

    fun find(name: String): AgentTool? = all.firstOrNull { it.name == name }

    /** For the system prompt: the category keys Gemini may use. */
    suspend fun categoryList(): String {
        val cats = categories.observeAll().first().filter { !it.archived }
        fun list(kind: CategoryKind) = cats.filter { it.kind == kind }.joinToString { "${it.iconKey}: ${it.name}" }
        return "Spending categories (key: name): ${list(CategoryKind.EXPENSE)}. Income categories: ${list(CategoryKind.INCOME)}."
    }

    // ---- tools ----

    private fun searchTransactions() = AgentTool(
        "search_transactions",
        "Find entries (payments, income) by text, category, type and date range. Use it before editing or deleting, to get ids.",
        schema {
            str("query", "Words in the payee, note or category")
            str("category", "Category key or name")
            enumOf("type", listOf("expense", "income"))
            str("from", "YYYY-MM-DD"); str("to", "YYYY-MM-DD")
            int("limit", "At most this many, newest first (default 20)")
        },
        confirm = false, summary = { "" },
    ) { a ->
        val q = a.text("query")?.lowercase(Locale.ROOT)
        val category = a.text("category")?.let { findCategory(it, null) }
        val type = a.text("type")?.let(::typeOf)
        val from = a.date("from"); val to = a.date("to")
        val found = transactions.observeAll().first().asSequence()
            .filter { it.source != TxSource.CHECKIN }
            .filter { type == null || it.type == type }
            .filter { category == null || it.category.id == category.id }
            .filter { tx -> q == null || listOfNotNull(tx.merchant, tx.note, tx.category.name).any { it.lowercase(Locale.ROOT).contains(q) } }
            .filter { tx -> val d = dateOf(tx); (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to)) }
            .toList()
        val shown = found.take((a.int("limit") ?: 20).coerceIn(1, 50))
        buildJsonObject {
            put("count", found.size)
            put("total", found.sumOf { it.amountMinor } / 100.0)
            putJsonArray("entries") { shown.forEach { add(entryJson(it)) } }
        }
    }

    private fun addTransaction() = AgentTool(
        "add_transaction", "Add a payment (expense) or money received (income).",
        schema(required = listOf("type", "amount", "category")) {
            enumOf("type", listOf("expense", "income")); num("amount", "Positive amount"); str("category", "Category key")
            str("payee", "Who was paid or who paid"); str("note", "Short note"); str("date", "YYYY-MM-DD, default today")
        },
        confirm = true,
        summary = { a ->
            val type = typeOf(a.text("type"))
            listOfNotNull(
                if (type == TxType.INCOME) "Add income" else "Add expense",
                money(a.minor("amount")), findCategory(a.text("category"), kindOf(type))?.name, a.text("payee"), a.text("note"), dayLabel(a.date("date")),
            ).joinToString(" · ")
        },
    ) { a ->
        val type = typeOf(a.text("type"))
        val category = findCategory(a.text("category"), kindOf(type)) ?: fallback(type)
        val date = a.date("date") ?: clock.today()
        val id = transactions.add(
            TransactionDraft(
                type = type, amountMinor = a.minor("amount") ?: throw ToolProblem("amount missing"), currency = currency(),
                categoryId = category.id, merchant = a.text("payee"), note = a.text("note"), occurredAt = timeOn(date), source = TxSource.MANUAL,
            ),
        )
        buildJsonObject { put("added", id) }
    }

    private fun editTransaction() = AgentTool(
        "edit_transaction", "Change an entry found with search_transactions: amount, category, date, payee or note.",
        schema(required = listOf("id")) {
            int("id", "Entry id"); num("amount", "New amount"); str("category", "New category key"); str("date", "New date YYYY-MM-DD")
            str("payee", "New payee"); str("note", "New note")
        },
        confirm = true,
        summary = { a ->
            val tx = transactions.get(a.long("id") ?: -1)
            val changes = listOfNotNull(
                a.minor("amount")?.let { "amount ${money(it)}" }, a.text("category")?.let { "category ${findCategory(it, null)?.name ?: it}" },
                a.date("date")?.let { "date ${dayLabel(it)}" }, a.text("payee")?.let { "payee $it" }, a.text("note")?.let { "note \"$it\"" },
            )
            "Change ${tx?.let { describe(it) } ?: "entry"}: ${changes.joinToString()}"
        },
    ) { a ->
        val tx = transactions.get(a.long("id") ?: -1) ?: throw ToolProblem("no entry with that id")
        val category = a.text("category")?.let { findCategory(it, tx.category.kind) ?: throw ToolProblem("unknown category $it") }
        transactions.update(
            tx.id,
            tx.toDraft().copy(
                amountMinor = a.minor("amount") ?: tx.amountMinor, categoryId = category?.id ?: tx.category.id,
                occurredAt = a.date("date")?.let(::timeOn) ?: tx.occurredAt, merchant = a.text("payee") ?: tx.merchant, note = a.text("note") ?: tx.note,
            ),
        )
        buildJsonObject { put("updated", tx.id) }
    }

    private fun deleteTransaction() = AgentTool(
        "delete_transaction", "Delete an entry found with search_transactions (it stays 30 days in Recently deleted).",
        schema(required = listOf("id")) { int("id", "Entry id") },
        confirm = true,
        summary = { a -> "Delete ${transactions.get(a.long("id") ?: -1)?.let { describe(it) } ?: "entry"} (restorable for 30 days)" },
    ) { a ->
        transactions.delete(a.long("id") ?: -1) ?: throw ToolProblem("no entry with that id")
        buildJsonObject { put("deleted", true) }
    }

    private fun setPayeeCategory() = AgentTool(
        "set_payee_category", "File every payment to a payee under a category, now and for future payments.",
        schema(required = listOf("payee", "category")) { str("payee", "Payee name"); str("category", "Category key") },
        confirm = true,
        summary = { a -> "File all payments to ${a.text("payee")} (${payeeEntries(a.text("payee")).size}) under ${findCategory(a.text("category"), null)?.name ?: a.text("category")}" },
    ) { a ->
        val entries = payeeEntries(a.text("payee"))
        val first = entries.firstOrNull() ?: throw ToolProblem("no payments to ${a.text("payee")}")
        val category = findCategory(a.text("category"), first.category.kind) ?: throw ToolProblem("unknown category ${a.text("category")}")
        transactions.update(first.id, first.toDraft().copy(categoryId = category.id), samePayeeToo = true)
        buildJsonObject { put("entries", entries.size) }
    }

    private fun setSalary() = AgentTool(
        "set_salary", "Set the salary for a month (used for savings: salary minus money moved to Revolut). It is not an entry.",
        schema(required = listOf("amount")) { num("amount", "Salary amount"); str("month", "YYYY-MM, default this month") },
        confirm = true,
        summary = { a -> "Salary for ${a.text("month") ?: "this month"}: ${money(a.minor("amount"))}" },
    ) { a ->
        val s = settings.settings.first()
        val day = a.text("month")?.let { runCatching { YearMonth.parse(it).atDay(maxOf(1, s.periodStartDay)) }.getOrNull() } ?: clock.today()
        plans.setSalary(BudgetPeriods.periodFor(day, s.periodStartDay), s.currency, a.minor("amount") ?: throw ToolProblem("amount missing"))
        buildJsonObject { put("ok", true) }
    }

    private fun setMonthlyBudget() = AgentTool(
        "set_monthly_budget", "Set how much the user wants to spend this month in total.",
        schema(required = listOf("amount")) { num("amount", "Monthly spending goal") },
        confirm = true, summary = { a -> "Monthly spending goal: ${money(a.minor("amount"))}" },
    ) { a ->
        val s = settings.settings.first()
        plans.setGoal(BudgetPeriods.periodFor(clock.today(), s.periodStartDay), a.minor("amount") ?: throw ToolProblem("amount missing"))
        buildJsonObject { put("ok", true) }
    }

    private fun setCategoryBudget() = AgentTool(
        "set_category_budget", "Set (or remove with 0) a monthly limit for one spending category.",
        schema(required = listOf("category", "amount")) { str("category", "Category key"); num("amount", "Monthly limit, 0 to remove") },
        confirm = true,
        summary = { a -> "${findCategory(a.text("category"), CategoryKind.EXPENSE)?.name ?: a.text("category")}: limit ${a.minor("amount")?.takeIf { it > 0 }?.let { money(it) } ?: "removed"} a month" },
    ) { a ->
        val category = findCategory(a.text("category"), CategoryKind.EXPENSE) ?: throw ToolProblem("unknown category")
        categories.setMonthlyLimit(category.id, a.minor("amount")?.takeIf { it > 0 })
        buildJsonObject { put("ok", true) }
    }

    private fun addSubscription() = AgentTool(
        "add_subscription", "Track a bill or subscription (reminders, planning). It never adds entries: the real payments come from the bank.",
        schema(required = listOf("name", "amount", "cadence")) {
            str("name", "Name"); num("amount", "Amount per charge"); enumOf("cadence", listOf("weekly", "monthly", "quarterly", "yearly"))
            str("next_date", "Next charge YYYY-MM-DD"); bool("varies", "True when the amount changes (phone, energy)"); str("category", "Category key")
        },
        confirm = true,
        summary = { a -> "Track ${a.text("name")}: ${money(a.minor("amount"))} ${a.text("cadence")}" + (a.date("next_date")?.let { ", next ${dayLabel(it)}" } ?: "") + if (a.bool("varies") == true) " (amount varies)" else "" },
    ) { a ->
        val cycle = cycleOf(a.text("cadence"))
        val category = findCategory(a.text("category"), CategoryKind.EXPENSE) ?: findCategory("subscriptions", CategoryKind.EXPENSE) ?: fallback(TxType.EXPENSE)
        val next = a.date("next_date")?.takeIf { it.isAfter(clock.today()) } ?: BillingSchedule.nextAfter(clock.today(), cycle, clock.today())
        val id = subscriptions.add(
            SubscriptionDraft(
                name = a.text("name") ?: throw ToolProblem("name missing"), amountMinor = a.minor("amount") ?: throw ToolProblem("amount missing"),
                currency = currency(), cycle = cycle, nextCharge = next, categoryId = category.id, remindDaysBefore = 1, autoLog = false,
                colorKey = category.colorKey, amountVaries = a.bool("varies") == true,
                payeeKey = a.text("name")?.let(MerchantKey::of),
            ),
        )
        buildJsonObject { put("added", id) }
    }

    private fun removeSubscription() = AgentTool(
        "remove_subscription", "Stop tracking a subscription or bill by name (a detected one is also marked as not a bill).",
        schema(required = listOf("name")) { str("name", "Subscription name") },
        confirm = true, summary = { a -> "Stop tracking ${findSubscription(a.text("name"))?.name ?: a.text("name")}" },
    ) { a ->
        val sub = findSubscription(a.text("name")) ?: throw ToolProblem("no subscription named ${a.text("name")}")
        if (sub.detected) subscriptions.dismissDetected(sub.id) else subscriptions.setStatus(sub.id, SubscriptionStatus.CANCELLED)
        buildJsonObject { put("ok", true) }
    }

    private fun addPending() = AgentTool(
        "add_pending", "Remember money the user owes someone (i_owe) or that someone owes them (owed_to_me), with an optional due date.",
        schema(required = listOf("direction", "title", "amount")) {
            enumOf("direction", listOf("i_owe", "owed_to_me")); str("title", "What it is for"); str("person", "Who"); num("amount", "Amount"); str("due", "YYYY-MM-DD")
        },
        confirm = true,
        summary = { a -> (if (a.text("direction") == "owed_to_me") "${a.text("person") ?: "Someone"} owes you" else "You owe ${a.text("person") ?: "someone"}") + " ${money(a.minor("amount"))} · ${a.text("title")}" + (a.date("due")?.let { " · due ${dayLabel(it)}" } ?: "") },
    ) { a ->
        val id = pendings.add(
            PendingDraft(
                title = a.text("title") ?: throw ToolProblem("title missing"), counterparty = a.text("person"),
                direction = if (a.text("direction") == "owed_to_me") PendingDirection.OWED_TO_ME else PendingDirection.I_OWE,
                amountMinor = a.minor("amount") ?: throw ToolProblem("amount missing"), currency = currency(), due = a.date("due"),
            ),
        )
        buildJsonObject { put("added", id) }
    }

    private fun setLowFundsWarning() = AgentTool(
        "set_low_funds_warning", "Choose what the low-funds warning compares bills with.",
        schema(required = listOf("mode")) { enumOf("mode", listOf("balance", "balance_and_spending", "budget", "off")) },
        confirm = true, summary = { a -> "Low-funds warning: ${a.text("mode")?.replace('_', ' ')}" },
    ) { a ->
        settings.setLowFunds(LowFundsMode.entries.firstOrNull { it.name.equals(a.text("mode"), ignoreCase = true) } ?: throw ToolProblem("unknown mode"))
        buildJsonObject { put("ok", true) }
    }

    private fun syncBank() = AgentTool(
        "sync_bank", "Fetch the latest payments from Revolut now.", schema {}, confirm = false, summary = { "" },
    ) { _ ->
        BankSyncWorker.runNow(context)
        buildJsonObject { put("started", true) }
    }

    private fun analysePayments() = AgentTool(
        "analyse_payments", "Re-run payee recognition and bill detection on the payment history.", schema {}, confirm = false, summary = { "" },
    ) { _ ->
        AiWorker.runNow(context)
        buildJsonObject { put("started", true) }
    }

    private fun openScreen() = AgentTool(
        "open_screen", "Open a screen of the app for the user.",
        schema(required = listOf("screen")) { enumOf("screen", AgentScreen.entries.map { it.name.lowercase(Locale.ROOT) }) },
        confirm = false, summary = { "" },
    ) { a ->
        val screen = AgentScreen.entries.firstOrNull { it.name.equals(a.text("screen"), ignoreCase = true) } ?: throw ToolProblem("unknown screen")
        onOpenScreen(screen)
        buildJsonObject { put("opened", screen.name.lowercase(Locale.ROOT)) }
    }

    // ---- helpers ----

    private suspend fun currency() = settings.settings.first().currency

    private suspend fun money(minor: Long?): String = minor?.let { formatter.format(it, currency()) } ?: "?"

    private fun dateOf(tx: Transaction): LocalDate = Instant.ofEpochMilli(tx.occurredAt).atZone(clock.zone).toLocalDate()

    private fun timeOn(date: LocalDate): Long =
        if (date == clock.today()) clock.millis() else date.atTime(12, 0).atZone(clock.zone).toInstant().toEpochMilli()

    private fun dayLabel(date: LocalDate?): String? = when (date) {
        null, clock.today() -> if (date == null) null else "today"
        clock.today().minusDays(1) -> "yesterday"
        else -> date.toString()
    }

    private suspend fun describe(tx: Transaction) = "${tx.title} ${money(tx.amountMinor)} on ${dateOf(tx)}"

    private fun entryJson(tx: Transaction) = buildJsonObject {
        put("id", tx.id); put("date", dateOf(tx).toString()); put("payee", tx.title); put("category", tx.category.iconKey)
        put("amount", tx.amountMinor / 100.0); put("type", if (tx.type == TxType.EXPENSE) "expense" else "income")
        tx.note?.let { put("note", it) }
    }

    private suspend fun findCategory(text: String?, kind: CategoryKind?): Category? {
        val t = text?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        val cats = categories.observeAll().first().filter { !it.archived && (kind == null || it.kind == kind) }
        return cats.firstOrNull { it.iconKey == t } ?: cats.firstOrNull { it.name.lowercase(Locale.ROOT) == t }
            ?: cats.firstOrNull { it.name.lowercase(Locale.ROOT).contains(t) || t.contains(it.iconKey) }
            ?: kind?.let { categories.ensure(t, it) }
    }

    private suspend fun fallback(type: TxType): Category =
        findCategory(if (type == TxType.EXPENSE) "other" else "other_income", kindOf(type)) ?: throw ToolProblem("no category")

    private suspend fun payeeEntries(payee: String?): List<Transaction> {
        val p = payee?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val key = MerchantKey.of(p)
        return transactions.observeAll().first().filter { tx ->
            tx.merchant != null && (MerchantKey.of(tx.merchant) == key || tx.merchant.contains(p, ignoreCase = true))
        }
    }

    private suspend fun findSubscription(name: String?) = name?.trim()?.takeIf { it.isNotBlank() }?.let { n ->
        val subs = subscriptions.all().filter { it.status != SubscriptionStatus.CANCELLED }
        subs.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: subs.firstOrNull { it.name.contains(n, ignoreCase = true) || n.contains(it.name, ignoreCase = true) }
    }

    private fun typeOf(text: String?) = if (text.equals("income", ignoreCase = true)) TxType.INCOME else TxType.EXPENSE
    private fun kindOf(type: TxType) = if (type == TxType.EXPENSE) CategoryKind.EXPENSE else CategoryKind.INCOME
    private fun cycleOf(text: String?) = when (text?.lowercase(Locale.ROOT)) {
        "weekly" -> Cycle(CycleUnit.WEEK, 1)
        "quarterly" -> Cycle(CycleUnit.MONTH, 3)
        "yearly" -> Cycle(CycleUnit.YEAR, 1)
        else -> Cycle.Monthly
    }

    private suspend fun JsonObject.minor(key: String): Long? =
        num(key)?.takeIf { it > 0 }?.let { Currencies.toMinor(String.format(Locale.ROOT, "%.2f", it), currency()) }

    companion object {
        fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
        fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.toInt() }
        fun JsonObject.long(key: String): Long? = num(key)?.toLong()
        fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        fun JsonObject.date(key: String): LocalDate? = text(key)?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

        /** A JSON schema object for a tool's parameters. */
        fun schema(required: List<String> = emptyList(), build: SchemaBuilder.() -> Unit): JsonObject {
            val props = SchemaBuilder().apply(build).props
            return buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(props))
                if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }

    class SchemaBuilder {
        val props = linkedMapOf<String, JsonObject>()
        fun str(name: String, description: String) { props[name] = buildJsonObject { put("type", "string"); put("description", description) } }
        fun num(name: String, description: String) { props[name] = buildJsonObject { put("type", "number"); put("description", description) } }
        fun int(name: String, description: String) { props[name] = buildJsonObject { put("type", "integer"); put("description", description) } }
        fun bool(name: String, description: String) { props[name] = buildJsonObject { put("type", "boolean"); put("description", description) } }
        fun enumOf(name: String, values: List<String>) {
            props[name] = buildJsonObject { put("type", "string"); putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } } }
        }
    }
}
