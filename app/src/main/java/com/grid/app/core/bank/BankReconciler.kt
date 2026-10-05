package com.grid.app.core.bank

import androidx.room.withTransaction
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.Seed
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.data.db.entities.PendingPaymentEntity
import com.grid.app.core.data.db.entities.SubscriptionEntity
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.model.Category
import com.grid.app.core.data.repo.PendingRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.BankTxKind
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.Cycle
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.PaymentKind
import com.grid.app.core.model.PendingDirection
import com.grid.app.core.model.PendingStatus
import com.grid.app.core.model.SubscriptionStatus
import com.grid.app.core.model.TransactionDraft
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BillingSchedule
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Decides what one newly fetched bank transaction means for the ledger (spec §5), in order:
 * not spending (own pockets) → the settled version of something already recorded → a bill not logged yet →
 * a new entry (card payments, direct debits, learned transfers) → ask the user.
 */
@Singleton
class BankReconciler @Inject constructor(
    private val db: GridDatabase,
    private val transactions: TransactionRepository,
    private val pending: PendingRepository,
    private val categories: CategoryRepository,
    private val clock: AppClock,
) {
    private val dao = db.bankDao()

    private data class Outcome(val state: BankTxState, val transactionId: Long? = null)

    suspend fun process(row: BankTransactionEntity, appCurrency: String): BankTxState = db.withTransaction {
        val outcome = decide(row, appCurrency)
        dao.updateStaged(row.copy(state = outcome.state, transactionId = outcome.transactionId))
        outcome.state
    }

    private suspend fun decide(row: BankTransactionEntity, appCurrency: String): Outcome {
        if (row.kind == BankTxKind.INTERNAL) return Outcome(BankTxState.IGNORED)
        if (row.currency != appCurrency) return Outcome(BankTxState.NEEDS_DECISION)
        // Money between the user's own accounts: tracked as "moved to Revolut", never income or spending.
        if (isOwnTransfer(row)) return Outcome(BankTxState.OWN_TRANSFER)

        val type = if (row.direction == CaptureDirection.OUT) TxType.EXPENSE else TxType.INCOME
        // What Gemini learned about this payee: its real name, and a category when nobody taught one.
        val profile = row.counterpartyKey?.let { db.payeeProfileDao().get(it) }
        val merchant = profile?.name ?: BankRepository.displayName(row)
        val methods = db.paymentMethodDao().all().filter { !it.archived }
        fun method(kind: PaymentKind?) = kind?.let { k -> methods.firstOrNull { it.kind == k }?.id }
        val viaMethod = method(row.via)
        val revolut = method(PaymentKind.REVOLUT)

        val wantedKind = if (type == TxType.EXPENSE) CategoryKind.EXPENSE else CategoryKind.INCOME
        val rule = merchant?.let(MerchantKey::of)?.let { db.merchantRuleDao().get(it) }
        val ruleCategory = rule?.let { db.categoryDao().get(it.categoryId) }?.takeIf { !it.archived && it.kind == wantedKind }

        // 1. Already in the ledger (notification capture, manual entry, logged bill, check-in income).
        val entries = dao.ledgerCandidates(type, row.currency, row.occurredAt - WINDOW, row.occurredAt + WINDOW)
        val hit = MatchRules.bestMatch(
            BankFacts(type, row.kind, row.amountMinor, row.occurredAt, merchant, ruleCategory?.id),
            entries.map { Candidate(it.id, it.source, it.amountMinor, it.occurredAt, it.merchant ?: it.note, it.categoryId) },
        )
        if (hit != null) {
            val entry = entries.first { it.id == hit.id }
            val currentKind = entry.paymentMethodId?.let { id -> methods.firstOrNull { it.id == id }?.kind }
            // "Paid with PayPal" beats a generic Revolut/card label; a Google Wallet label from the capture is kept.
            val override = viaMethod?.takeIf { currentKind == null || currentKind == PaymentKind.REVOLUT || currentKind == PaymentKind.CARD }
            transactions.applyBank(hit.id, row.amountMinor, override)
            return Outcome(BankTxState.BOOKED, hit.id)
        }

        // 2. A bill Grid knows about but hasn't logged for this date.
        if (row.direction == CaptureDirection.OUT) {
            matchSubscription(row, merchant)?.let { sub ->
                val id = book(row, merchant, sub.categoryId, sub.paymentMethodId ?: viaMethod ?: revolut, subscriptionId = sub.id)
                return Outcome(BankTxState.BOOKED, id)
            }
        }
        matchPending(row, merchant)?.let { p ->
            pending.settle(p.id, row.amountMinor, row.occurredAt)?.let { return Outcome(BankTxState.BOOKED, it) }
        }

        // 3. Everything else is real money in or out: always booked, so Activity shows it all. The category comes from
        //    what the user taught, else the bank's code, else the name; when nothing knows, "Other" (marked to sort).
        val profileCategory = profile?.categoryIconKey?.takeIf { wantedKind == CategoryKind.EXPENSE }?.let { categories.ensure(it, wantedKind) }
        val guessedId = ruleCategory?.id ?: profileCategory?.id ?: guessCategory(row, merchant, wantedKind)?.id
        val categoryId = guessedId
            ?: db.categoryDao().byIconKey(if (type == TxType.EXPENSE) Seed.ICON_OTHER else Seed.ICON_OTHER_INCOME, wantedKind)!!.id
        val id = book(row, merchant, categoryId, rule?.paymentMethodId ?: viaMethod ?: revolut, needsReview = guessedId == null)
        return Outcome(BankTxState.BOOKED, id)
    }

    /**
     * Re-checks, with today's rules, what earlier versions left undecided or filed under "Other": the user's own
     * money becomes a move, a recognised merchant gets its category. What the user sorted themselves is never touched.
     */
    suspend fun revisit(appCurrency: String, ownIbans: Set<String>) {
        fun reclassified(row: BankTransactionEntity) =
            row.copy(kind = RemoteTxJson.parse(row.rawJson)?.let { TxClassifier.classify(it, ownIbans) } ?: row.kind)

        for (row in dao.stagedByState(BankTxState.NEEDS_DECISION)) {
            if (row.currency == appCurrency) process(reclassified(row).copy(state = BankTxState.NEW), appCurrency)
        }
        // Earlier versions kept money sent to the user's own accounts aside as a move: it is spending, so book it.
        for (row in dao.stagedByState(BankTxState.OWN_TRANSFER).filter { it.direction == CaptureDirection.OUT }) {
            process(row.copy(state = BankTxState.NEW, countInEpochDay = null), appCurrency)
        }
        for (tx in dao.bankNeedsReview()) {
            val row = dao.stagedLinkedTo(tx.id)?.let(::reclassified) ?: continue
            if (row.kind == BankTxKind.INTERNAL || isOwnTransfer(row)) {
                db.withTransaction {
                    dao.updateStaged(row.copy(state = if (row.kind == BankTxKind.INTERNAL) BankTxState.IGNORED else BankTxState.OWN_TRANSFER, transactionId = null))
                    db.transactionDao().delete(tx.id)
                }
                continue
            }
            val wantedKind = if (tx.type == TxType.EXPENSE) CategoryKind.EXPENSE else CategoryKind.INCOME
            val category = guessCategory(row, BankRepository.displayName(row), wantedKind) ?: continue
            dao.updateStaged(row)
            transactions.recategorize(tx.id, category.id)
        }
    }

    /**
     * Money coming in from the holder's own name or a learned own account (e.g. the salary bank). Money going out,
     * even to the user's own accounts, is spending (the owner's rule) and is booked like any payment.
     */
    private suspend fun isOwnTransfer(row: BankTransactionEntity): Boolean {
        if (row.direction != CaptureDirection.IN || row.kind !in setOf(BankTxKind.TOP_UP, BankTxKind.MONEY_IN)) return false
        if (row.counterpartyKey?.let { dao.ownAccountRule(it) } != null) return true
        // A card top-up names no one ("Top-Up by *1234"): the user's own card elsewhere.
        if (row.kind == BankTxKind.TOP_UP && (row.counterparty == null || row.counterparty.contains("top-up", ignoreCase = true))) return true
        return OwnerMatch.isOwner(row.counterparty, dao.accountHolderNames())
    }

    private suspend fun guessCategory(row: BankTransactionEntity, merchant: String?, kind: CategoryKind): Category? {
        val iconKey = when (row.kind) {
            BankTxKind.CASH_WITHDRAWAL -> "cash"
            // Refunds, and "top-ups" that aren't the user's own money (an insurer or energy supplier paying back).
            BankTxKind.REFUND, BankTxKind.TOP_UP -> Seed.ICON_REFUND
            BankTxKind.MONEY_IN -> null
            else -> MccCategories.iconKeyFor(row.mcc)
                ?: MerchantCategorizer.iconKeyFor(merchant, isTransfer = row.kind == BankTxKind.TRANSFER_OUT || row.kind == BankTxKind.DIRECT_DEBIT)
                ?: MerchantCategorizer.iconKeyFor(row.counterparty, isTransfer = row.kind == BankTxKind.TRANSFER_OUT || row.kind == BankTxKind.DIRECT_DEBIT)
        } ?: return null
        return categories.ensure(iconKey, kind)
    }

    private suspend fun book(
        row: BankTransactionEntity, merchant: String?, categoryId: Long, methodId: Long?,
        subscriptionId: Long? = null, needsReview: Boolean = false,
    ): Long = transactions.add(
        TransactionDraft(
            type = if (row.direction == CaptureDirection.OUT) TxType.EXPENSE else TxType.INCOME,
            amountMinor = row.amountMinor, currency = row.currency, categoryId = categoryId, paymentMethodId = methodId,
            merchant = merchant, occurredAt = row.occurredAt, source = TxSource.BANK,
            subscriptionId = subscriptionId, needsReview = needsReview,
        ),
    )

    /** An active subscription charging about this amount within ±3 days that has no entry for that charge yet. */
    private suspend fun matchSubscription(row: BankTransactionEntity, merchant: String?): SubscriptionEntity? {
        // A bill linked to this payee: whatever the day, and any amount when it varies (phone, energy).
        db.subscriptionDao().all().firstOrNull {
            it.status == SubscriptionStatus.ACTIVE && it.currency == row.currency && it.payeeKey != null && it.payeeKey == row.counterpartyKey &&
                (it.amountVaries || MatchRules.relativeDiff(row.amountMinor, it.amountMinor) <= 0.25)
        }?.let { return it }
        val day = Instant.ofEpochMilli(row.occurredAt).atZone(clock.zone).toLocalDate()
        val from = day.minusDays(SLACK_DAYS)
        val to = day.plusDays(SLACK_DAYS)
        return db.subscriptionDao().all()
            .filter { it.status == SubscriptionStatus.ACTIVE && it.currency == row.currency }
            .filter { amountFits(row, it.amountMinor, listOf(it.name), merchant) }
            .filter { sub -> BillingSchedule.nextOnOrAfter(LocalDate.ofEpochDay(sub.anchorEpochDay), Cycle(sub.cycleUnit, sub.cycleCount), from) <= to }
            .filter { sub -> db.transactionDao().countForSubscriptionBetween(sub.id, startOf(from), startOf(to.plusDays(1))) == 0 }
            .minByOrNull { MatchRules.relativeDiff(row.amountMinor, it.amountMinor) }
    }

    /** An open "I owe" (money out) or "owed to me" (money in) of about this amount, due around this date. */
    private suspend fun matchPending(row: BankTransactionEntity, merchant: String?): PendingPaymentEntity? {
        val direction = if (row.direction == CaptureDirection.OUT) PendingDirection.I_OWE else PendingDirection.OWED_TO_ME
        val day = Instant.ofEpochMilli(row.occurredAt).atZone(clock.zone).toLocalDate().toEpochDay()
        return db.pendingDao().all()
            .filter { it.status == PendingStatus.PENDING && it.direction == direction && it.currency == row.currency }
            .filter { p -> p.dueEpochDay == null || abs(p.dueEpochDay - day) <= SLACK_DAYS }
            .filter { amountFits(row, it.amountMinor, listOfNotNull(it.counterparty, it.title), merchant) }
            .minByOrNull { MatchRules.relativeDiff(row.amountMinor, it.amountMinor) }
    }

    /** Within 10% when a name matches; within 2% on amount alone, for transfers only. */
    private fun amountFits(row: BankTransactionEntity, amountMinor: Long, names: List<String>, merchant: String?): Boolean {
        val diff = MatchRules.relativeDiff(row.amountMinor, amountMinor)
        val named = merchant != null && names.any { MatchRules.similar(it, merchant) }
        return (named && diff <= 0.10) || (MatchRules.isTransfer(row.kind) && diff <= 0.02)
    }

    private fun startOf(date: LocalDate): Long = date.atStartOfDay(clock.zone).toInstant().toEpochMilli()

    private companion object {
        val WINDOW = TimeUnit.DAYS.toMillis(7)
        const val SLACK_DAYS = 3L
    }
}
