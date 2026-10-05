package com.grid.app.core.ai

import com.grid.app.core.bank.OwnerMatch
import com.grid.app.core.bills.RecurringDetector
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.Seed
import com.grid.app.core.data.db.entities.PayeeProfileEntity
import com.grid.app.core.data.db.entities.TransactionEntity
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.SubscriptionRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.BankTxKind
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.SubscriptionDraft
import com.grid.app.core.model.SubscriptionStatus
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BillingSchedule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AiRunResult {
    data class Ok(val payees: Int, val bills: Int) : AiRunResult
    data object NoKey : AiRunResult
    data class Failed(val error: AiError) : AiRunResult
}

/**
 * Gemini's pass over the payment history, after each sync and on demand:
 * 1. payees it hasn't seen get their real name, what they are, and a category (never over the user's own choice);
 * 2. recurring bills (fixed or varying) become subscriptions marked "detected", linked to their payee and payments.
 * What is sent: payee names, amounts and dates. Never the account holder's name, an IBAN or the balance.
 */
@Singleton
class AiAssistant @Inject constructor(
    private val db: GridDatabase,
    private val gemini: GeminiClient,
    private val keys: AiKeyStore,
    private val transactions: TransactionRepository,
    private val subscriptions: SubscriptionRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) {
    private val mutex = Mutex()
    private val profiles = db.payeeProfileDao()
    private val bank = db.bankDao()

    suspend fun run(): AiRunResult = mutex.withLock {
        if (!keys.hasKey()) return AiRunResult.NoKey
        return try {
            transactions.markTaughtRules()
            recognizePayees()
            val bills = findBills()
            val known = profiles.all().size
            settings.recordAiRun(clock.millis(), known, bills)
            AiRunResult.Ok(known, bills)
        } catch (e: AiError) {
            settings.recordAiError(e.message ?: e.javaClass.simpleName)
            AiRunResult.Failed(e)
        }
    }

    /** One tiny request: is the key accepted? */
    suspend fun test(): AiError? = try {
        gemini.askJson("""Answer with JSON only: {"ok": true}""")
        null
    } catch (e: AiError) {
        e
    }

    private suspend fun holderNames() = bank.accountHolderNames()

    private suspend fun recognizePayees() {
        val holders = holderNames()
        val rows = bank.unprofiledPayments().filter { !OwnerMatch.isOwner(it.counterparty, holders) }
        if (rows.isEmpty()) return
        val groups = rows.groupBy { it.counterpartyKey!! }
        val facts = groups.map { (key, rs) ->
            PayeeFacts(
                key = key, raw = BankRepository.displayName(rs.first()) ?: key,
                kind = when (rs.first().kind) { BankTxKind.TRANSFER_OUT -> "transfer"; BankTxKind.DIRECT_DEBIT -> "direct debit"; else -> "card" },
                payments = rs.size, typical = rs.map { it.amountMinor }.sorted()[rs.size / 2] / 100.0,
            )
        }.sortedByDescending { it.payments }
        val choices = categoryChoices()
        val valid = choices.map { it.first }.toSet()
        for (batch in facts.chunked(RECOGNITION_BATCH)) {
            val answers = AiPrompts.parseRecognition(gemini.askJson(AiPrompts.recognition(batch, choices))).associateBy { it.key }
            val now = clock.millis()
            val learned = batch.map { f ->
                val a = answers[f.key]
                PayeeProfileEntity(
                    key = f.key,
                    name = a?.name?.takeIf { a.confidence >= NAME_CONFIDENCE } ?: f.raw,
                    about = a?.about,
                    categoryIconKey = a?.category?.takeIf { a.confidence >= CATEGORY_CONFIDENCE && it in valid && it != Seed.ICON_OTHER },
                    updatedAt = now,
                )
            }
            profiles.upsert(learned)
            for (p in learned) {
                val ids = groups[p.key].orEmpty().mapNotNull { it.transactionId }
                val categoryId = p.categoryIconKey?.let { categories.ensure(it, CategoryKind.EXPENSE)?.id }
                transactions.applyPayeeProfile(ids, p.name, categoryId)
            }
        }
    }

    /** Returns how many detected bills are active. */
    private suspend fun findBills(): Int {
        val today = clock.today()
        val currency = settings.settings.first().currency
        val since = today.minusDays(HISTORY_DAYS)
        val zone = clock.zone
        fun dateOf(tx: TransactionEntity): LocalDate = Instant.ofEpochMilli(tx.occurredAt).atZone(zone).toLocalDate()

        val holders = holderNames()
        val iconOf = db.categoryDao().all().associate { it.id to it.iconKey }
        val payeeOfTx = bank.bookedWithEntry().associate { it.transactionId!! to it.counterpartyKey!! }
        val known = profiles.all().associateBy { it.key }
        val subs = db.subscriptionDao().all()
        // Bills the user tracks by hand are theirs: not detected again.
        val manual = subs.filter { !it.detected }.flatMap { listOfNotNull(it.payeeKey, MerchantKey.of(it.name)) }.toSet()

        val groups = db.transactionDao().withMerchant(TxType.EXPENSE)
            .filter { it.currency == currency && it.source != TxSource.CHECKIN && !dateOf(it).isBefore(since) }
            .groupBy { payeeOfTx[it.id] ?: MerchantKey.of(it.merchant!!) ?: it.merchant!! }
            .filter { (key, txs) ->
                txs.size in 2..MAX_PAYMENTS && key !in manual && known[key]?.notABill != true &&
                    !OwnerMatch.isOwner(txs.first().merchant, holders) &&
                    iconOf[txs.maxBy { it.occurredAt }.categoryId] !in RecurringDetector.notBills
            }
        if (groups.isEmpty()) return subs.count { it.detected && it.status == SubscriptionStatus.ACTIVE }

        val histories = groups.map { (key, txs) ->
            PaymentHistory(
                key = key, name = known[key]?.name ?: txs.maxBy { it.occurredAt }.merchant!!, about = known[key]?.about,
                payments = txs.sortedBy { it.occurredAt }.takeLast(PAYMENTS_SENT).map { listOf(dateOf(it).toString(), String.format(Locale.ROOT, "%.2f", it.amountMinor / 100.0)) },
            )
        }
        val found = histories.chunked(BILLS_BATCH).flatMap { batch ->
            AiPrompts.parseBills(gemini.askJson(AiPrompts.bills(today, currency, batch)), currency)
        }.filter { it.confidence >= BILL_CONFIDENCE && it.key in groups }

        for (bill in found) {
            val txs = groups.getValue(bill.key)
            val existing = subs.firstOrNull { it.detected && it.payeeKey == bill.key }
            if (!bill.active) {
                existing?.takeIf { it.status == SubscriptionStatus.ACTIVE }?.let { subscriptions.setStatus(it.id, SubscriptionStatus.CANCELLED) }
                continue
            }
            val last = txs.maxBy { it.occurredAt }
            val unlinked = txs.filter { it.subscriptionId == null }.map { it.id }
            if (existing == null) {
                val categoryId = known[bill.key]?.categoryIconKey?.let { categories.ensure(it, CategoryKind.EXPENSE)?.id } ?: last.categoryId
                val color = db.categoryDao().get(categoryId)?.colorKey ?: "violet"
                val id = subscriptions.add(
                    SubscriptionDraft(
                        name = known[bill.key]?.name ?: bill.name ?: last.merchant!!, amountMinor = bill.amountMinor, currency = currency,
                        cycle = bill.cycle, nextCharge = BillingSchedule.nextAfter(dateOf(last), bill.cycle, today), categoryId = categoryId,
                        paymentMethodId = last.paymentMethodId, remindDaysBefore = 1, autoLog = false, colorKey = color,
                        payeeKey = bill.key, amountVaries = bill.varies, detected = true,
                    ),
                )
                subscriptions.linkPayments(id, unlinked)
            } else {
                // The expected amount follows a varying bill; what the user edited otherwise stays.
                if (existing.amountVaries || bill.varies) subscriptions.refreshExpected(existing.id, bill.amountMinor, bill.varies)
                if (unlinked.isNotEmpty()) subscriptions.linkPayments(existing.id, unlinked)
            }
        }
        return db.subscriptionDao().all().count { it.detected && it.status == SubscriptionStatus.ACTIVE }
    }

    /** The categories Gemini may choose from: the user's active spending categories and Grid's standard ones. */
    private suspend fun categoryChoices(): List<Pair<String, String>> {
        val own = db.categoryDao().all().filter { it.kind == CategoryKind.EXPENSE && !it.archived }.map { it.iconKey to it.name }
        val seed = Seed.categories.filter { it.kind == CategoryKind.EXPENSE }.map { it.iconKey to it.name }
        return (own + seed).distinctBy { it.first }
    }

    companion object {
        private const val RECOGNITION_BATCH = 50
        private const val BILLS_BATCH = 40
        private const val HISTORY_DAYS = 400L
        /** More payments than this in a year is everyday spending, not a bill. */
        private const val MAX_PAYMENTS = 30
        private const val PAYMENTS_SENT = 12
        private const val NAME_CONFIDENCE = 0.7
        private const val CATEGORY_CONFIDENCE = 0.5
        private const val BILL_CONFIDENCE = 0.7
    }
}
