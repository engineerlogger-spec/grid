package com.grid.app.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.data.db.entities.BankConnectionEntity
import com.grid.app.core.model.BankStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * People's history must survive every schema change. Builds a populated v1 file exactly as Room v1 created it
 * (from the exported schema), then opens it with the current database, which migrates and validates it.
 * (MigrationTestHelper can't read schema assets under Robolectric, which only sees the app's merged assets.)
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun v1ToV2KeepsTransactionsAndAddsBankTables() = runTest {
        val file = context.getDatabasePath("migration-test.db").also { it.parentFile!!.mkdirs(); it.delete() }
        createV1(file)

        val db = Room.databaseBuilder(context, GridDatabase::class.java, file.absolutePath).build()
        try {
            val tx = db.transactionDao().get(7)!!
            assertThat(tx.amountMinor).isEqualTo(2340)
            assertThat(tx.needsReview).isFalse()
            val id = db.bankDao().insertConnection(
                BankConnectionEntity(provider = "enablebanking", aspspName = "Revolut", aspspCountry = "FR", status = BankStatus.NEEDS_SETUP, createdAt = 1),
            )
            assertThat(db.bankDao().connection()!!.id).isEqualTo(id)
        } finally {
            db.close()
        }
    }

    private fun createV1(file: File) {
        val schema = Json.parseToJsonElement(File(SCHEMA_V1).readText()).jsonObject["database"]!!.jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schema["entities"]!!.jsonArray.forEach { entity ->
                val e = entity.jsonObject
                val table = e["tableName"]!!.jsonPrimitive.content
                db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("INSERT INTO categories (id, name, iconKey, colorKey, kind, position, archived) VALUES (1, 'Groceries', 'groceries', 'mint', 'EXPENSE', 0, 0)")
            db.execSQL(
                """INSERT INTO transactions (id, type, amountMinor, currency, categoryId, occurredAt, createdAt, updatedAt, source)
                   VALUES (7, 'EXPENSE', 2340, 'EUR', 1, 1000, 1000, 1000, 'MANUAL')""",
            )
            db.version = 1
        }
    }

    private companion object {
        /** Gradle runs unit tests from the module directory. */
        const val SCHEMA_V1 = "schemas/com.grid.app.core.data.db.GridDatabase/1.json"
    }
}
