package com.grid.app.core.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.data.db.entities.TransactionEntity
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.TxType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GridDatabaseTest {

    private lateinit var db: GridDatabase

    @Before fun setUp() {
        db = GridDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() = db.close()

    private suspend fun categoryId(iconKey: String, kind: CategoryKind = CategoryKind.EXPENSE) =
        db.categoryDao().byIconKey(iconKey, kind)!!.id

    private fun tx(categoryId: Long, amount: Long, at: Long, note: String? = null, methodId: Long? = null, type: TxType = TxType.EXPENSE) =
        TransactionEntity(type = type, amountMinor = amount, currency = "EUR", categoryId = categoryId, paymentMethodId = methodId,
            note = note, occurredAt = at, createdAt = at, updatedAt = at)

    @Test fun seedsCategoriesAndMethods() = runTest {
        val categories = db.categoryDao().all()
        assertThat(categories).hasSize(20)
        assertThat(categories.count { it.kind == CategoryKind.INCOME }).isEqualTo(4)
        assertThat(categories.filter { it.kind == CategoryKind.EXPENSE }.map { it.position }).isEqualTo((0..15).toList())
        assertThat(db.paymentMethodDao().all().map { it.name })
            .containsExactly("Card", "Cash", "Google Wallet", "PayPal", "Revolut").inOrder()
    }

    @Test fun observeBetweenIsHalfOpen() = runTest {
        val food = categoryId("restaurant")
        db.transactionDao().insert(tx(food, 100, at = 1_000))
        db.transactionDao().insert(tx(food, 200, at = 2_000))
        db.transactionDao().insert(tx(food, 300, at = 3_000))
        val inRange = db.transactionDao().observeBetween(1_000, 3_000).first()
        assertThat(inRange.map { it.amountMinor }).containsExactly(200L, 100L).inOrder()
    }

    @Test fun categoryUsageOrdersByCount() = runTest {
        val food = categoryId("restaurant")
        val groceries = categoryId("groceries")
        repeat(3) { db.transactionDao().insert(tx(groceries, 100, at = 10L + it)) }
        db.transactionDao().insert(tx(food, 100, at = 20))
        val usage = db.transactionDao().categoryUsage(TxType.EXPENSE, sinceMs = 0)
        assertThat(usage.map { it.categoryId }).containsExactly(groceries, food).inOrder()
        assertThat(usage.first().uses).isEqualTo(3)
    }

    @Test fun suggestionsNeedTwoUsesAndGroupCaseInsensitively() = runTest {
        val food = categoryId("restaurant")
        db.transactionDao().insert(tx(food, 350, at = 10, note = "Coffee"))
        db.transactionDao().insert(tx(food, 350, at = 20, note = "coffee "))
        db.transactionDao().insert(tx(food, 1200, at = 30, note = "Lunch"))
        val rows = db.transactionDao().suggestionRows(sinceMs = 0, limit = 4)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().amountMinor).isEqualTo(350)
        assertThat(rows.single().uses).isEqualTo(2)
    }

    @Test fun lastManualPaymentMethod() = runTest {
        val food = categoryId("restaurant")
        val methods = db.paymentMethodDao().all()
        db.transactionDao().insert(tx(food, 100, at = 10, methodId = methods[0].id))
        db.transactionDao().insert(tx(food, 100, at = 20, methodId = methods[4].id))
        assertThat(db.transactionDao().lastManualPaymentMethodId()).isEqualTo(methods[4].id)
    }

    @Test fun deletingUsedCategoryIsRestricted() = runTest {
        val food = categoryId("restaurant")
        db.transactionDao().insert(tx(food, 100, at = 10))
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { db.categoryDao().delete(food) }
        }
    }
}
