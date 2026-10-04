package com.grid.app.core.bank

import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
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
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

sealed interface SyncResult {
    data class Ok(val fetched: Int, val booked: Int, val toReview: Int) : SyncResult
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
) {
    private val mutex = Mutex()
    private val dao = db.bankDao()

    suspend fun run(): SyncResult = mutex.withLock {
        val connection = bank.connection()
        if (connection?.sessionId == null || connection.status == BankStatus.NEEDS_SETUP) return SyncResult.NotConnected
        if (connection.validUntil != null && connection.validUntil < clock.millis()) {
            bank.markStatus(BankStatus.EXPIRED)
            return SyncResult.Expired
        }
        val connector = connectors.current() ?: return SyncResult.NotConnected
        val currency = settings.settings.first().currency
        val accounts = bank.accounts()
        val ownIbans = accounts.mapNotNull { it.iban }.toSet()
        val today = clock.today()
        val reviewBefore = dao.countToReview()
        var fetched = 0

        try {
            for (account in accounts.filter { it.enabled }) {
                val from = account.syncedThroughEpochDay?.let { LocalDate.ofEpochDay(it).minusDays(OVERLAP_DAYS) }
                    ?: connection.backfillFromEpochDay?.let(LocalDate::ofEpochDay)
                    ?: today.minusDays(DEFAULT_BACKFILL_DAYS)
                val all = mutableListOf<RemoteTx>()
                var key: String? = null
                do {
                    val page = connector.transactions(account.uid, from, key)
                    all += page.transactions
                    key = page.continuationKey
                } while (key != null)
                fetched += all.size

                val booked = all.filter { it.status == null || it.status.equals("BOOK", ignoreCase = true) }
                for ((externalId, tx) in ExternalIds.assign(booked)) {
                    toEntity(account.id, externalId, tx, ownIbans)?.let { dao.insertStaged(it) }
                }
                bank.updateAccount(account.copy(syncedThroughEpochDay = today.toEpochDay()))
            }
        } catch (e: BankError) {
            return when (e) {
                is BankError.SessionExpired, is BankError.Unauthorized -> {
                    bank.markStatus(BankStatus.EXPIRED, e.message)
                    SyncResult.Expired
                }
                is BankError.RateLimited -> SyncResult.RateLimited
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

        // Includes rows left NEW by an interrupted run.
        var bookedCount = 0
        for (row in dao.stagedByState(BankTxState.NEW).sortedBy { it.occurredAt }) {
            if (reconciler.process(row, currency) == BankTxState.BOOKED) bookedCount++
        }
        bank.recordSync(clock.millis())
        return SyncResult.Ok(fetched, bookedCount, (dao.countToReview() - reviewBefore).coerceAtLeast(0))
    }

    private fun toEntity(accountId: Long, externalId: String, tx: RemoteTx, ownIbans: Set<String>): BankTransactionEntity? {
        val date = parseDate(tx.transactionDate) ?: parseDate(tx.bookingDate) ?: parseDate(tx.valueDate) ?: return null
        val booking = parseDate(tx.bookingDate) ?: date
        val amount = runCatching { abs(Currencies.toMinor(tx.amount.trim().removePrefix("-").removePrefix("+"), tx.currency)) }.getOrNull()
        if (amount == null || amount == 0L) return null
        val cleaned = tx.counterparty?.let(DescriptorCleaner::clean)
        val noon = date.atTime(12, 0).atZone(clock.zone).toInstant().toEpochMilli()
        return BankTransactionEntity(
            accountId = accountId, externalId = externalId, bookingEpochDay = booking.toEpochDay(),
            occurredAt = minOf(noon, clock.millis()), amountMinor = amount, currency = tx.currency,
            direction = if (tx.isCredit) CaptureDirection.IN else CaptureDirection.OUT,
            kind = TxClassifier.classify(tx, ownIbans), counterparty = tx.counterparty,
            counterpartyKey = cleaned?.merchant?.let(MerchantKey::of), counterpartyIban = tx.counterpartyIban,
            description = tx.remittance.joinToString(" · ").ifBlank { null }, mcc = tx.mcc, via = cleaned?.via,
            state = BankTxState.NEW, rawJson = tx.rawJson, createdAt = clock.millis(),
        )
    }

    private fun parseDate(text: String?): LocalDate? = text?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    companion object {
        /** Where the bank's login sends the user back (a static page that forwards to the app). */
        const val REDIRECT_URL = "https://engineerlogger-spec.github.io/grid/bank-callback/"
        private const val OVERLAP_DAYS = 5L
        private const val DEFAULT_BACKFILL_DAYS = 90L
    }
}
