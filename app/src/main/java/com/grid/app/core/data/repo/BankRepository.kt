package com.grid.app.core.data.repo

import androidx.room.withTransaction
import com.grid.app.core.bank.Aspsp
import com.grid.app.core.bank.BankSession
import com.grid.app.core.bank.DescriptorCleaner
import com.grid.app.core.bank.OwnTransfer
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.BankAccountEntity
import com.grid.app.core.data.db.entities.BankConnectionEntity
import com.grid.app.core.data.db.entities.OwnAccountRuleEntity
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.model.BankStatus
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.PaymentKind
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TransactionDraft
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class ReviewKind { CATEGORISE, DECIDE_OUT, DECIDE_IN }

/**
 * Bank items waiting for the user, grouped so one tap settles them all: card payments booked without a known
 * category (CATEGORISE, ids are transactions) and transfers / money in waiting for a decision (DECIDE_*, ids are
 * staged bank rows).
 */
data class BankReviewGroup(
    val key: String,
    val kind: ReviewKind,
    val title: String,
    val counterpartyKey: String?,
    val count: Int,
    val totalMinor: Long,
    val currency: String,
    val latestAt: Long,
    val ids: List<Long>,
)

data class AuthResult(val accounts: List<BankAccountEntity>, val firstConnect: Boolean)

@Singleton
class BankRepository @Inject constructor(
    private val db: GridDatabase,
    private val transactions: TransactionRepository,
    private val clock: AppClock,
) {
    private val dao = db.bankDao()

    /** Bank payments whose ledger entry was deleted (before "Recently deleted" kept a copy): restorable from the bank. */
    fun observeOrphans(): Flow<List<BankTransactionEntity>> = dao.observeOrphans()

    /**
     * A bank payment can lose its link while its entry still exists (an older Undo put the entry back unlinked):
     * link it again to an unlinked entry of the same amount around the same day, so it isn't offered as deleted.
     */
    suspend fun relinkOrphans() {
        val day = TimeUnit.DAYS.toMillis(1)
        for (row in dao.orphans()) {
            val type = if (row.direction == CaptureDirection.OUT) TxType.EXPENSE else TxType.INCOME
            dao.unlinkedEntries(type, row.amountMinor, row.currency, row.occurredAt - day, row.occurredAt + day)
                .firstOrNull()?.let { dao.updateStaged(row.copy(transactionId = it.id)) }
        }
    }

    fun observeConnection(): Flow<BankConnectionEntity?> = dao.observeConnection()
    suspend fun connection(): BankConnectionEntity? = dao.connection()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAccounts(): Flow<List<BankAccountEntity>> =
        dao.observeConnection().flatMapLatest { c -> if (c == null) flowOf(emptyList()) else dao.observeAccounts(c.id) }

    suspend fun accounts(): List<BankAccountEntity> = dao.connection()?.let { dao.accounts(it.id) }.orEmpty()

    /** Remembers the `state` of an authorisation about to open in the browser (survives the app being killed meanwhile). */
    suspend fun beginAuth(aspsp: Aspsp, state: String, backfillFromEpochDay: Long?) {
        val existing = dao.connection()
        if (existing == null) {
            dao.insertConnection(
                BankConnectionEntity(
                    provider = PROVIDER, aspspName = aspsp.name, aspspCountry = aspsp.country, status = BankStatus.NEEDS_SETUP,
                    authState = state, backfillFromEpochDay = backfillFromEpochDay, createdAt = clock.millis(),
                ),
            )
        } else {
            dao.updateConnection(
                existing.copy(
                    aspspName = aspsp.name, aspspCountry = aspsp.country, authState = state,
                    backfillFromEpochDay = backfillFromEpochDay ?: existing.backfillFromEpochDay,
                ),
            )
        }
    }

    /**
     * Stores the session the bank granted. Accounts are matched by their stable identification hash, so a reconnect
     * keeps each account's choice and sync position; new accounts start enabled only in the app's currency.
     */
    suspend fun completeAuth(state: String, session: BankSession, appCurrency: String): AuthResult {
        val connection = dao.connection() ?: error("No bank connection in progress")
        check(connection.authState != null && connection.authState == state) { "Unexpected bank authorisation" }
        return db.withTransaction {
            val existing = dao.accounts(connection.id).associateBy { it.identificationHash }
            dao.updateConnection(
                connection.copy(
                    sessionId = session.sessionId, validUntil = session.validUntil, status = BankStatus.ACTIVE,
                    authState = null, lastError = null,
                ),
            )
            session.accounts.forEach { remote ->
                val old = existing[remote.identificationHash]
                if (old != null) {
                    // A fresh approval is the moment the bank gives the whole history: fetch it all again (ids de-duplicate).
                    dao.updateAccount(
                        old.copy(uid = remote.uid, name = remote.name ?: old.name, currency = remote.currency, iban = remote.iban ?: old.iban, syncedThroughEpochDay = null),
                    )
                } else {
                    dao.upsertAccount(
                        BankAccountEntity(
                            connectionId = connection.id, uid = remote.uid, identificationHash = remote.identificationHash,
                            name = remote.name, currency = remote.currency, iban = remote.iban, enabled = remote.currency == appCurrency,
                        ),
                    )
                }
            }
            AuthResult(dao.accounts(connection.id), firstConnect = existing.isEmpty())
        }
    }

    suspend fun setEnabled(accountId: Long, enabled: Boolean) {
        val account = dao.account(accountId) ?: return
        dao.updateAccount(account.copy(enabled = enabled))
    }

    suspend fun updateAccount(account: BankAccountEntity) = dao.updateAccount(account)

    suspend fun markStatus(status: BankStatus, error: String? = null) {
        val c = dao.connection() ?: return
        dao.updateConnection(c.copy(status = status, lastError = error))
    }

    /** A failed sync that keeps the connection usable (network, bank hiccup). */
    suspend fun recordError(message: String) {
        val c = dao.connection() ?: return
        dao.updateConnection(c.copy(lastError = message))
    }

    suspend fun recordSync(at: Long) {
        val c = dao.connection() ?: return
        dao.updateConnection(c.copy(lastSyncAt = at, lastError = null, status = BankStatus.ACTIVE))
    }

    /**
     * A payment notified in the last 15 minutes that the bank doesn't list yet (no row of that exact amount, direction
     * and currency within two days): worth asking the bank again shortly.
     */
    suspend fun awaitingListing(): Boolean {
        val now = clock.millis()
        return db.captureDao().paymentsSince(now - AWAIT_MS).any { c ->
            dao.stagedBetween(c.postedAt - LISTED_WINDOW_MS, c.postedAt + LISTED_WINDOW_MS).none {
                it.direction == c.direction && it.amountMinor == c.amountMinor && it.currency == c.currency && it.state != BankTxState.IGNORED
            }
        }
    }

    /**
     * Ends bank access but keeps what was fetched: the history is what prevents duplicates if the user
     * connects the same accounts again.
     */
    suspend fun disconnect() {
        val c = dao.connection() ?: return
        dao.updateConnection(c.copy(sessionId = null, validUntil = null, status = BankStatus.NEEDS_SETUP, authState = null, lastError = null))
    }

    fun observeReviewGroups(): Flow<List<BankReviewGroup>> =
        combine(dao.observeNeedsReview(), dao.observeStagedByState(BankTxState.NEEDS_DECISION)) { needsReview, staged ->
            val categorise = needsReview.groupBy { MerchantKey.of(it.merchant.orEmpty()) ?: "?" }.map { (key, rows) ->
                BankReviewGroup(
                    key = "c:$key", kind = ReviewKind.CATEGORISE, title = rows.first().merchant ?: "?", counterpartyKey = key,
                    count = rows.size, totalMinor = rows.sumOf { it.amountMinor }, currency = rows.first().currency,
                    latestAt = rows.maxOf { it.occurredAt }, ids = rows.map { it.id },
                )
            }
            val decide = staged.groupBy { Triple(it.direction, it.counterpartyKey ?: "?", it.currency) }.map { (k, rows) ->
                val (direction, key, currency) = k
                BankReviewGroup(
                    key = "${direction.name}:$key:$currency",
                    kind = if (direction == CaptureDirection.OUT) ReviewKind.DECIDE_OUT else ReviewKind.DECIDE_IN,
                    title = displayName(rows.first()) ?: "?", counterpartyKey = rows.first().counterpartyKey,
                    count = rows.size, totalMinor = rows.sumOf { it.amountMinor }, currency = currency,
                    latestAt = rows.maxOf { it.occurredAt }, ids = rows.map { it.id },
                )
            }
            (categorise + decide).sortedByDescending { it.latestAt }
        }

    /** Gives every transaction in the group its category; the edit also teaches the merchant rule. */
    suspend fun categorise(group: BankReviewGroup, categoryId: Long) {
        group.ids.forEach { id ->
            val tx = transactions.get(id) ?: return@forEach
            transactions.update(id, tx.toDraft().copy(categoryId = categoryId))
        }
    }

    /** Books the staged rows; the merchant rule this teaches makes later transfers to the same person automatic. */
    suspend fun book(group: BankReviewGroup, categoryId: Long) {
        val revolut = methodId(PaymentKind.REVOLUT)
        group.ids.forEach { id ->
            val row = dao.staged(id)?.takeIf { it.state == BankTxState.NEEDS_DECISION } ?: return@forEach
            // The real salary replaces the amount estimated at the monthly check-in (and teaches who pays it).
            checkInEstimate(row, categoryId)?.let { estimate ->
                transactions.update(estimate.id, estimate.toDraft().copy(amountMinor = row.amountMinor, merchant = displayName(row)))
                dao.updateStaged(row.copy(state = BankTxState.BOOKED, transactionId = estimate.id))
                return@forEach
            }
            val txId = transactions.add(
                TransactionDraft(
                    type = if (row.direction == CaptureDirection.OUT) TxType.EXPENSE else TxType.INCOME,
                    amountMinor = row.amountMinor, currency = row.currency, categoryId = categoryId,
                    paymentMethodId = row.via?.let { methodId(it) } ?: revolut, merchant = displayName(row),
                    occurredAt = row.occurredAt, source = TxSource.BANK,
                ),
            )
            dao.updateStaged(row.copy(state = BankTxState.BOOKED, transactionId = txId))
        }
    }

    /** The unlinked check-in income of this category within a week of the money arriving, if any. */
    private suspend fun checkInEstimate(row: BankTransactionEntity, categoryId: Long): Transaction? {
        if (row.direction != CaptureDirection.IN) return null
        val week = TimeUnit.DAYS.toMillis(7)
        val entry = dao.ledgerCandidates(TxType.INCOME, row.currency, row.occurredAt - week, row.occurredAt + week)
            .filter { it.source == TxSource.CHECKIN && it.categoryId == categoryId }
            .minByOrNull { abs(it.occurredAt - row.occurredAt) } ?: return null
        return transactions.get(entry.id)
    }

    /** Leaves these payments out, this once. */
    suspend fun ignore(group: BankReviewGroup) {
        group.ids.forEach { id ->
            val row = dao.staged(id)?.takeIf { it.state == BankTxState.NEEDS_DECISION } ?: return@forEach
            dao.updateStaged(row.copy(state = BankTxState.IGNORED))
        }
    }

    /**
     * "This is my own account" (e.g. the bank the salary is paid into): money from it, now and later, is counted as
     * moved to Revolut. Money sent to it stays spending (the owner's rule: whatever leaves Revolut is spent).
     */
    suspend fun markOwnAccount(group: BankReviewGroup) {
        if (group.kind == ReviewKind.CATEGORISE) return
        val key = group.counterpartyKey?.takeIf { it != "?" }
        val rows = (if (key != null) dao.stagedByState(BankTxState.NEEDS_DECISION).filter { it.counterpartyKey == key }
        else group.ids.mapNotNull { dao.staged(it) }.filter { it.state == BankTxState.NEEDS_DECISION })
            .filter { it.direction == CaptureDirection.IN }
        rows.forEach { dao.updateStaged(it.copy(state = BankTxState.OWN_TRANSFER)) }
        if (key != null) dao.insertOwnAccountRule(OwnAccountRuleEntity(key, clock.millis()))
    }

    /** Money moved into Revolut from the user's own accounts. */
    fun observeOwnTransfers(): Flow<List<OwnTransfer>> = dao.observeStagedByState(BankTxState.OWN_TRANSFER).map { rows ->
        rows.filter { it.direction == CaptureDirection.IN }.map { row ->
            OwnTransfer(
                id = row.id, date = Instant.ofEpochMilli(row.occurredAt).atZone(clock.zone).toLocalDate(),
                amountMinor = row.amountMinor, incoming = row.direction == CaptureDirection.IN,
                countIn = row.countInEpochDay?.let(LocalDate::ofEpochDay), counterparty = displayName(row),
            )
        }
    }

    /** Counts an own transfer in the period containing [date] (null: back to the period of its own date). */
    suspend fun countIn(transferId: Long, date: LocalDate?) = dao.setCountIn(transferId, date?.toEpochDay())

    /** The last fetched transactions exactly as the bank sent them, to tune the parsers from real data. */
    suspend fun recentRaw(limit: Int = 20): String = dao.recent(limit).joinToString(",\n", "[\n", "\n]") { it.rawJson }

    suspend fun methodId(kind: PaymentKind): Long? = db.paymentMethodDao().all().firstOrNull { it.kind == kind && !it.archived }?.id

    companion object {
        const val PROVIDER = "enablebanking"
        private val AWAIT_MS = TimeUnit.MINUTES.toMillis(15)
        private val LISTED_WINDOW_MS = TimeUnit.DAYS.toMillis(2)

        /** The name to show and to learn rules by: the cleaned counterparty ("PAYPAL *NETFLIX" → Netflix). */
        fun displayName(row: BankTransactionEntity): String? =
            row.counterparty?.let { DescriptorCleaner.clean(it)?.merchant } ?: row.counterparty
    }
}
