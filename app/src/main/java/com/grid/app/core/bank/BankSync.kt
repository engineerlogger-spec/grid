package com.grid.app.core.bank

import androidx.room.withTransaction
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.BankConnectionEntity
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.BankStatus
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.money.Currencies
import com.grid.app.core.time.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * PRESENT: the person asked for it in the app (pull to refresh, Sync now, opening Grid, Ask Grid); the bank is told so
 * and doesn't count it against its daily background limit. BACKGROUND: Grid on its own (after a payment, every 6 hours).
 */
enum class SyncMode { PRESENT, BACKGROUND }

sealed interface SyncResult {
    /** [newRowIds]: the bank's rows of payments listed for the first time in this sync (not settlements of earlier ones). */
    data class Ok(val fetched: Int, val booked: Int, val toReview: Int, val newRowIds: List<Long> = emptyList()) : SyncResult
    data object NotConnected : SyncResult
    data object Expired : SyncResult
    data object RateLimited : SyncResult
    data class Failed(val message: String) : SyncResult
}

/**
 * Fetches booked transactions for every enabled account and reconciles the new ones. Each run re-reads a few days
 * before the last sync (late bookings); stable external ids make the overlap harmless.
 */
@Singleton
class BankSync @Inject constructor(
    private val db: GridDatabase,
    private val bank: BankRepository,
    private val connectors: BankConnectorProvider,
    private val reconciler: BankReconciler,
    private val settings: SettingsRepository,
    private val clock: AppClock,
    private val transactions: TransactionRepository,
    private val reversals: Reversals,
    /** Optional only so tests about other rules can leave it out; the app always provides it. */
    private val presence: PresenceProvider? = null,
) {
    private val mutex = Mutex()
    private val dao = db.bankDao()

    suspend fun run(mode: SyncMode = SyncMode.BACKGROUND): SyncResult = mutex.withLock {
        val connection = bank.connection()
        if (connection?.sessionId == null || connection.status == BankStatus.NEEDS_SETUP) return SyncResult.NotConnected
        if (connection.validUntil != null && connection.validUntil < clock.millis()) {
            bank.markStatus(BankStatus.EXPIRED)
            return SyncResult.Expired
        }
        // The bank refused a background sync not long ago: Grid waits; what the person asks for still goes through.
        if (mode == SyncMode.BACKGROUND && settings.bankPausedUntil() > clock.millis()) return SyncResult.RateLimited
        val base = connectors.current() ?: return SyncResult.NotConnected
        val connector = if (mode == SyncMode.PRESENT) presence?.now()?.let { p -> (base as? EnableBankingClient)?.present(p) } ?: base else base
        val currency = settings.settings.first().currency
        val accounts = bank.accounts()
        val ownIbans = accounts.mapNotNull { it.iban }.toSet()
        val today = clock.today()
        val reviewBefore = dao.countToReview()
        var fetched = 0
        val newRowIds = mutableListOf<Long>()

        try {
            for (account in accounts.filter { it.enabled }) {
                // First fetch after the user approves access: the whole history (banks only allow it in that window).
                // Afterwards: from a few days before the last sync, so late bookings are caught.
                // Also from the oldest payment still pending, so a missing one really means the bank dropped it.
                val longest = account.syncedThroughEpochDay == null
                val oldestPending = dao.stagedWithPrefix(account.id, PENDING_PREFIX).filter { it.state != BankTxState.REVERTED }
                    .minOfOrNull { it.bookingEpochDay }?.coerceAtLeast(today.minusDays(PENDING_DAYS).toEpochDay())
                val from = account.syncedThroughEpochDay?.let { LocalDate.ofEpochDay(minOf(it - OVERLAP_DAYS, oldestPending ?: it)) }
                val all = mutableListOf<RemoteTx>()
                var key: String? = null
                do {
                    val page = connector.transactions(account.uid, from, key, longest)
                    all += page.transactions
                    key = page.continuationKey
                } while (key != null)
                fetched += all.size

                // Everything the bank sends is stored; what is shown or counted is decided afterwards (row state).
                val inserted = mutableListOf<BankTransactionEntity>()
                for ((externalId, tx) in ExternalIds.assign(all.filter { it.isBooked })) {
                    val row = toEntity(account.id, externalId, tx, ownIbans)
                    val id = dao.insertStaged(row)
                    // A pending payment taken for reverted that the bank books after all: its entry comes back.
                    if (id > 0 && !reversals.bookedAfterAll(row.copy(id = id))) inserted += row.copy(id = id)
                }
                // Card payments Revolut hasn't settled yet show straight away; each is replaced by its booked version.
                val pendingNow = ExternalIds.assign(all.filter { it.isPending }).map { (id, tx) -> PENDING_PREFIX + id to tx }
                val stillPending = pendingNow.map { it.first }.toSet()
                for (old in dao.stagedWithPrefix(account.id, PENDING_PREFIX)) {
                    if (old.state != BankTxState.REVERTED && old.externalId !in stillPending) settle(old, inserted)
                }
                // Booked rows left over didn't settle a pending one: payments listed for the first time, like new pending ones.
                newRowIds += inserted.map { it.id }
                for ((externalId, tx) in pendingNow) {
                    val id = dao.insertStaged(toEntity(account.id, externalId, tx, ownIbans))
                    if (id > 0) newRowIds += id
                }
                reversals.stillListed(account.id, stillPending)
                // Any other status (scheduled, information…): kept, not shown. Cancelled or rejected: the payment is reverted.
                for ((externalId, tx) in ExternalIds.assign(all.filter { !it.isBooked && !it.isPending })) {
                    val id = dao.insertStaged(toEntity(account.id, "${tx.status}:$externalId", tx, ownIbans).copy(state = BankTxState.IGNORED))
                    if (id > 0 && tx.isCancelled) reversals.cancelled(account.id, externalId)
                }
                // The balance feeds the low-funds warning; a bank that won't give it never fails the sync.
                val balance = try {
                    RemoteBalance.pick(connector.balances(account.uid), account.currency)?.minor()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                bank.updateAccount(
                    account.copy(
                        syncedThroughEpochDay = today.toEpochDay(),
                        balanceMinor = balance ?: account.balanceMinor,
                        balanceAt = if (balance != null) clock.millis() else account.balanceAt,
                    ),
                )
            }
        } catch (e: BankError) {
            return when (e) {
                is BankError.SessionExpired, is BankError.Unauthorized -> {
                    bank.markStatus(BankStatus.EXPIRED, e.message)
                    SyncResult.Expired
                }
                is BankError.RateLimited -> {
                    // Enable Banking's advice: wait about 6 hours before asking again in the background.
                    if (mode == SyncMode.BACKGROUND) settings.pauseBank(clock.millis() + PAUSE_MS)
                    SyncResult.RateLimited
                }
                is BankError.Http, is BankError.Network -> {
                    bank.recordError(e.message ?: "Sync failed")
                    SyncResult.Failed(e.message ?: "Sync failed")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Anything the bank sends that we don't understand fails this run; it never takes the app down.
            bank.recordError(e.message ?: e.javaClass.simpleName)
            return SyncResult.Failed(e.message ?: e.javaClass.simpleName)
        }

        // Rules improve between versions: re-check what is still undecided or under Other.
        reconciler.revisit(currency, ownIbans)
        // Includes rows left NEW by an interrupted run.
        var bookedCount = 0
        for (row in dao.stagedByState(BankTxState.NEW).sortedBy { it.occurredAt }) {
            // A notified payment Grid took for reverted that the bank lists after all: its entry comes back.
            if (reversals.listedAfterAll(row)) continue
            if (reconciler.process(row, currency) == BankTxState.BOOKED) bookedCount++
        }
        unlisted(connection, clock.millis())
        bank.recordSync(clock.millis())
        return SyncResult.Ok(fetched, bookedCount, (dao.countToReview() - reviewBefore).coerceAtLeast(0), newRowIds)
    }

    /** Re-applies today's rules to what is already stored, without asking the bank (runs whenever the app opens). */
    suspend fun refreshLocal() = mutex.withLock {
        backfillTimes()
        bank.relinkOrphans()
        val currency = settings.settings.first().currency
        reconciler.revisit(currency, bank.accounts().mapNotNull { it.iban }.toSet())
        for (row in dao.stagedByState(BankTxState.NEW).sortedBy { it.occurredAt }) reconciler.process(row, currency)
        if (!settings.sameDescriptorGuessUndone()) {
            reversals.undoSameDescriptorGuess()
            settings.markSameDescriptorGuessUndone()
        }
        bank.connection()?.let { c -> c.lastSyncAt?.let { unlisted(c, it) } }
    }

    /** Notified payments the bank still didn't list at its last fetch were reverted (only where every account is read). */
    private suspend fun unlisted(connection: BankConnectionEntity, syncedAt: Long) {
        val accounts = bank.accounts()
        val synced = accounts.filter { it.enabled }.map { it.currency }.toSet() - accounts.filter { !it.enabled }.map { it.currency }.toSet()
        reversals.unlisted(connection.aspspName, synced, connection.createdAt, syncedAt)
    }

    /**
     * Payments stored before Grid read times from Revolut's ids get their real time, and so do their entries, unless
     * someone changed the entry's time since (the user moving it, a notification giving its own).
     */
    private suspend fun backfillTimes() {
        for (row in dao.allStaged()) {
            val day = Instant.ofEpochMilli(row.occurredAt).atZone(clock.zone).toLocalDate()
            val real = BankTime.fromId(row.externalId, day, clock.zone)?.takeIf { it != row.occurredAt } ?: continue
            db.withTransaction {
                row.transactionId?.let { db.transactionDao().get(it) }?.takeIf { it.occurredAt == row.occurredAt }
                    ?.let { db.transactionDao().update(it.copy(occurredAt = real)) }
                dao.updateStaged(row.copy(occurredAt = real))
            }
        }
    }

    /** Books again a bank payment whose entry was deleted before "Recently deleted" kept copies. */
    suspend fun rebook(rowId: Long) = mutex.withLock {
        val row = dao.staged(rowId)?.takeIf { it.state == BankTxState.BOOKED && it.transactionId == null } ?: return@withLock
        reconciler.process(row.copy(state = BankTxState.NEW), settings.settings.first().currency)
    }

    /**
     * A payment the bank no longer lists as pending: its booked version (same id, else same money and name within a
     * few days, the final amount of a tip or exchange rate within 15%) takes over its ledger entry and the user's
     * choices; with none, the bank reverted it.
     */
    private suspend fun settle(old: BankTransactionEntity, inserted: MutableList<BankTransactionEntity>) = db.withTransaction {
        fun near(it: BankTransactionEntity) = it.direction == old.direction && it.currency == old.currency && abs(it.occurredAt - old.occurredAt) <= SETTLE_WINDOW_MS
        val bookedVersion = inserted.firstOrNull { it.externalId == old.externalId.removePrefix(PENDING_PREFIX) }
            ?: inserted.firstOrNull {
                near(it) && it.amountMinor == old.amountMinor &&
                    (it.counterpartyKey == null || old.counterpartyKey == null || it.counterpartyKey == old.counterpartyKey)
            }
            ?: inserted.firstOrNull {
                near(it) && old.counterpartyKey != null && it.counterpartyKey == old.counterpartyKey &&
                    MatchRules.relativeDiff(it.amountMinor, old.amountMinor) <= SETTLE_AMOUNT_DIFF
            }
        when {
            bookedVersion != null -> {
                dao.deleteStaged(old.id)
                inserted.remove(bookedVersion)
                dao.updateStaged(bookedVersion.copy(state = old.state, transactionId = old.transactionId, countInEpochDay = old.countInEpochDay))
                old.transactionId?.let { if (bookedVersion.amountMinor != old.amountMinor) transactions.applyBank(it, bookedVersion.amountMinor, null) }
            }
            // Reverted (the shop released the card payment): shown as such, counted nowhere, whoever recorded it first.
            old.transactionId != null -> reversals.revertRow(old)
            else -> dao.deleteStaged(old.id)
        }
    }

    private fun toEntity(accountId: Long, externalId: String, tx: RemoteTx, ownIbans: Set<String>): BankTransactionEntity {
        val date = parseDate(tx.transactionDate) ?: parseDate(tx.bookingDate) ?: parseDate(tx.valueDate) ?: clock.today()
        val booking = parseDate(tx.bookingDate) ?: date
        // A row without a usable amount is kept (and its raw data) but never shown or counted.
        val amount = runCatching { abs(Currencies.toMinor(tx.amount.trim().removePrefix("-").removePrefix("+"), tx.currency)) }.getOrNull() ?: 0L
        val cleaned = tx.counterparty?.let(DescriptorCleaner::clean)
        // Revolut's ids carry the second of the payment; otherwise only the day is known.
        val at = BankTime.fromId(externalId, date, clock.zone) ?: BankTime.dayOnly(date, clock.zone, clock.millis())
        return BankTransactionEntity(
            accountId = accountId, externalId = externalId, bookingEpochDay = booking.toEpochDay(),
            occurredAt = at, amountMinor = amount, currency = tx.currency,
            direction = if (tx.isCredit) CaptureDirection.IN else CaptureDirection.OUT,
            kind = TxClassifier.classify(tx, ownIbans), counterparty = tx.counterparty,
            counterpartyKey = cleaned?.merchant?.let(MerchantKey::of), counterpartyIban = tx.counterpartyIban,
            description = tx.remittance.joinToString(" · ").ifBlank { null }, mcc = tx.mcc, via = cleaned?.via,
            state = if (amount == 0L) BankTxState.IGNORED else BankTxState.NEW, rawJson = tx.rawJson, createdAt = clock.millis(),
        )
    }

    private fun parseDate(text: String?): LocalDate? = text?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    companion object {
        /** Where the bank's login sends the user back (a static page that forwards to the app). */
        const val REDIRECT_URL = "https://engineerlogger-spec.github.io/grid/bank-callback/"
        private const val OVERLAP_DAYS = 5L
        /** External ids of rows stored while still pending. */
        const val PENDING_PREFIX = "p:"
        private val SETTLE_WINDOW_MS = TimeUnit.DAYS.toMillis(5)
        private const val SETTLE_AMOUNT_DIFF = 0.15
        /** Card payments pending longer than this are given up on (banks release them by then). */
        private const val PENDING_DAYS = 30L
        private val PAUSE_MS = TimeUnit.HOURS.toMillis(6)
    }
}
