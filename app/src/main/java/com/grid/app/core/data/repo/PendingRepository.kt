package com.grid.app.core.data.repo

import androidx.room.withTransaction
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.Seed
import com.grid.app.core.data.db.entities.PendingPaymentEntity
import com.grid.app.core.data.db.entities.TransactionEntity
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.PendingDirection
import com.grid.app.core.model.PendingDraft
import com.grid.app.core.model.PendingPayment
import com.grid.app.core.model.PendingStatus
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** One-off money to pay ("I owe") or to receive ("owed to me"). Settling books the real transaction. */
@Singleton
class PendingRepository @Inject constructor(
    private val db: GridDatabase,
    private val clock: AppClock,
    private val listeners: Set<@JvmSuppressWildcards LedgerListener>,
) {
    private val dao = db.pendingDao()
    private val categories: Flow<Map<Long, Category>> =
        db.categoryDao().observeAll().map { list -> list.associate { it.id to it.toDomain() } }

    fun observeAll(): Flow<List<PendingPayment>> =
        combine(dao.observeAll(), categories) { rows, cats -> rows.map { it.toDomain(cats) } }

    suspend fun get(id: Long): PendingPayment? = dao.get(id)?.toDomain(categories.first())

    suspend fun all(): List<PendingPayment> {
        val cats = categories.first()
        return dao.all().map { it.toDomain(cats) }
    }

    suspend fun add(draft: PendingDraft): Long = dao.insert(
        PendingPaymentEntity(
            title = draft.title.trim(), counterparty = draft.counterparty?.trim()?.ifBlank { null }, direction = draft.direction,
            amountMinor = draft.amountMinor, currency = draft.currency, dueEpochDay = draft.due?.toEpochDay(),
            categoryId = draft.categoryId, note = draft.note?.trim()?.ifBlank { null }, remindDaysBefore = draft.remindDaysBefore,
            createdAt = clock.millis(),
        ),
    )

    suspend fun update(id: Long, draft: PendingDraft) {
        val existing = dao.get(id) ?: return
        dao.update(
            existing.copy(
                title = draft.title.trim(), counterparty = draft.counterparty?.trim()?.ifBlank { null }, direction = draft.direction,
                amountMinor = draft.amountMinor, currency = draft.currency, dueEpochDay = draft.due?.toEpochDay(),
                categoryId = draft.categoryId, note = draft.note?.trim()?.ifBlank { null }, remindDaysBefore = draft.remindDaysBefore,
            ),
        )
    }

    /** Deleting the reminder keeps any transaction it produced: that money really moved. */
    suspend fun delete(id: Long) = dao.delete(id)

    /**
     * Marks paid/received and books the matching expense or income, linked both ways. Bank sync passes the real
     * [amountMinor] and date [at]. Returns the transaction id, or null if it was already settled.
     */
    suspend fun settle(id: Long, amountMinor: Long? = null, at: Long? = null): Long? {
        val pending = dao.get(id) ?: return null
        if (pending.status == PendingStatus.DONE) return null
        val now = clock.millis()
        val txId = db.withTransaction {
            val type = if (pending.direction == PendingDirection.I_OWE) TxType.EXPENSE else TxType.INCOME
            val wantedKind = if (type == TxType.EXPENSE) CategoryKind.EXPENSE else CategoryKind.INCOME
            val chosen = pending.categoryId?.let { db.categoryDao().get(it) }?.takeIf { it.kind == wantedKind }
            val categoryId = chosen?.id
                ?: db.categoryDao().byIconKey(if (type == TxType.EXPENSE) Seed.ICON_OTHER else Seed.ICON_OTHER_INCOME, wantedKind)!!.id
            val txId = db.transactionDao().insert(
                TransactionEntity(
                    type = type, amountMinor = amountMinor ?: pending.amountMinor, currency = pending.currency, categoryId = categoryId,
                    merchant = pending.counterparty, note = pending.title, occurredAt = at ?: now, createdAt = now, updatedAt = now,
                    source = TxSource.PENDING, pendingId = pending.id,
                ),
            )
            dao.update(pending.copy(status = PendingStatus.DONE, settledAt = at ?: now, transactionId = txId))
            txId
        }
        listeners.notifyAll()
        return txId
    }

    /** Undo a settle: removes the booked transaction and makes the item pending again. */
    suspend fun reopen(id: Long) {
        val pending = dao.get(id) ?: return
        db.withTransaction {
            pending.transactionId?.let { db.transactionDao().delete(it) }
            dao.update(pending.copy(status = PendingStatus.PENDING, settledAt = null, transactionId = null))
        }
        listeners.notifyAll()
    }

    private fun PendingPaymentEntity.toDomain(cats: Map<Long, Category>) = PendingPayment(
        id = id, title = title, counterparty = counterparty, direction = direction, amountMinor = amountMinor, currency = currency,
        due = dueEpochDay?.let(LocalDate::ofEpochDay), category = categoryId?.let { cats[it] }, note = note,
        remindDaysBefore = remindDaysBefore, status = status, settledAt = settledAt, transactionId = transactionId,
    )
}
