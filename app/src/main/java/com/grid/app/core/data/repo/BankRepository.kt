package com.grid.app.core.data.repo

import androidx.room.withTransaction
import com.grid.app.core.bank.Aspsp
import com.grid.app.core.bank.BankSession
import com.grid.app.core.bank.DescriptorCleaner
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.BankAccountEntity
import com.grid.app.core.data.db.entities.BankConnectionEntity
import com.grid.app.core.data.db.entities.BankIgnoreRuleEntity
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.model.BankStatus
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.PaymentKind
import com.grid.app.core.model.TransactionDraft
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

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
                    dao.updateAccount(old.copy(uid = remote.uid, name = remote.name ?: old.name, currency = remote.currency, iban = remote.iban ?: old.iban))
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

    suspend fun ignore(group: BankReviewGroup, always: Boolean) {
        group.ids.forEach { id ->
            val row = dao.staged(id)?.takeIf { it.state == BankTxState.NEEDS_DECISION } ?: return@forEach
            dao.updateStaged(row.copy(state = BankTxState.IGNORED))
        }
        val key = group.counterpartyKey
        if (always && group.kind != ReviewKind.CATEGORISE && key != null && key != "?") {
            dao.insertIgnoreRule(BankIgnoreRuleEntity(key, clock.millis()))
        }
    }

    /** The last fetched transactions exactly as the bank sent them, to tune the parsers from real data. */
    suspend fun recentRaw(limit: Int = 20): String = dao.recent(limit).joinToString(",\n", "[\n", "\n]") { it.rawJson }

    suspend fun methodId(kind: PaymentKind): Long? = db.paymentMethodDao().all().firstOrNull { it.kind == kind && !it.archived }?.id

    companion object {
        const val PROVIDER = "enablebanking"

        /** The name to show and to learn rules by: the cleaned counterparty ("PAYPAL *NETFLIX" → Netflix). */
        fun displayName(row: BankTransactionEntity): String? =
            row.counterparty?.let { DescriptorCleaner.clean(it)?.merchant } ?: row.counterparty
    }
}
