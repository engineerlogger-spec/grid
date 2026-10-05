package com.grid.app.core.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.BankAccountEntity
import com.grid.app.core.data.db.entities.BankConnectionEntity
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.model.BankStatus
import com.grid.app.core.model.BankTxKind
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.TransactionDraft
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.FixedClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RecentlyDeletedTest {

    private lateinit var db: GridDatabase
    private val clock = FixedClock(LocalDate.parse("2026-10-05"))
    private lateinit var transactions: TransactionRepository
    private lateinit var bank: BankRepository

    @Before fun setUp() {
        db = GridDatabase.inMemory(ApplicationProvider.getApplicationContext())
        transactions = TransactionRepository(db, clock, emptySet())
        bank = BankRepository(db, transactions, clock)
    }

    @After fun tearDown() = db.close()

    private suspend fun bankEntry(merchant: String, amount: Long): Pair<Long, Long> {
        val connection = db.bankDao().insertConnection(BankConnectionEntity(provider = "eb", aspspName = "Revolut", aspspCountry = "FR", status = BankStatus.ACTIVE, createdAt = 0))
        val account = db.bankDao().upsertAccount(BankAccountEntity(connectionId = connection, uid = "u$merchant", identificationHash = "h$merchant", currency = "EUR", enabled = true))
        val txId = transactions.add(
            TransactionDraft(TxType.EXPENSE, amount, "EUR", db.categoryDao().byIconKey("restaurant", CategoryKind.EXPENSE)!!.id, merchant = merchant, occurredAt = clock.millis(), source = TxSource.BANK),
        )
        val rowId = db.bankDao().insertStaged(
            BankTransactionEntity(
                accountId = account, externalId = "x$merchant", bookingEpochDay = 0, occurredAt = clock.millis(), amountMinor = amount, currency = "EUR",
                direction = CaptureDirection.OUT, kind = BankTxKind.CARD_SPEND, counterparty = merchant, state = BankTxState.BOOKED,
                transactionId = txId, rawJson = "{}", createdAt = 0,
            ),
        )
        return txId to rowId
    }

    @Test fun aDeletedEntryWaitsInRecentlyDeletedAndComesBackWithItsBankLink() = runTest {
        val (txId, rowId) = bankEntry("Berfin", 1_250)
        transactions.delete(txId)
        assertThat(transactions.observeAll().first()).isEmpty()
        assertThat(transactions.observeDeleted().first().single().tx.merchant).isEqualTo("Berfin")
        assertThat(bank.observeOrphans().first()).isEmpty() // waiting in the trash, not "removed earlier"

        transactions.restoreDeleted(txId)
        val back = transactions.get(txId)!!
        assertThat(back.category.iconKey).isEqualTo("restaurant")
        assertThat(db.bankDao().staged(rowId)!!.transactionId).isEqualTo(txId)
        assertThat(transactions.observeDeleted().first()).isEmpty()
    }

    @Test fun aBankPaymentDeletedBeforeTheTrashExistedIsOfferedAndAnUndoneOneIsRelinked() = runTest {
        val (lostTx, lostRow) = bankEntry("Maxicoffee", 120)
        val (undoneTx, undoneRow) = bankEntry("Starbucks", 450)
        // As older versions did: the entry deleted outright, the bank row left booked and unlinked.
        db.transactionDao().delete(lostTx)
        db.transactionDao().delete(undoneTx)
        // …and an older Undo put one back without its link.
        db.transactionDao().insert(
            com.grid.app.core.data.db.entities.TransactionEntity(
                id = undoneTx, type = TxType.EXPENSE, amountMinor = 450, currency = "EUR",
                categoryId = db.categoryDao().byIconKey("restaurant", CategoryKind.EXPENSE)!!.id, merchant = "Starbucks",
                occurredAt = clock.millis(), createdAt = 0, updatedAt = 0, source = TxSource.BANK,
            ),
        )
        bank.relinkOrphans()
        assertThat(db.bankDao().staged(undoneRow)!!.transactionId).isEqualTo(undoneTx)
        assertThat(bank.observeOrphans().first().map { it.id }).containsExactly(lostRow)
    }
}
