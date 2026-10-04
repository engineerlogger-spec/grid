package com.grid.app.feature.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.ThemeMode
import com.grid.app.core.model.TransactionDraft
import com.grid.app.core.model.TxType
import com.grid.app.core.time.FixedClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {

    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FixedClock(LocalDate.parse("2026-10-04"))

    @Test fun backupThenRestoreBringsDataAndSettingsBack() = runTest {
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("s.preferences_pb").also { it.delete() } }, Locale.FRANCE)
        settings.setThemeMode(ThemeMode.DARK)

        val db = GridDatabase.build(context)
        val transactions = TransactionRepository(db, clock, emptySet())
        val food = db.categoryDao().byIconKey("restaurant", CategoryKind.EXPENSE)!!.id
        val id = transactions.add(TransactionDraft(TxType.EXPENSE, 1250, "EUR", food, note = "Lunch", occurredAt = clock.millis()))
        val manager = BackupManager(context, db, settings, transactions, clock)

        val bytes = ByteArrayOutputStream().also { manager.writeArchive(it) }.toByteArray()
        transactions.delete(id)
        settings.setThemeMode(ThemeMode.LIGHT)

        val restored = manager.inspect(ByteArrayInputStream(bytes))
        assertThat(restored.manifest.counts["transactions"]).isEqualTo(1)
        assertThat(restored.manifest.schemaVersion).isEqualTo(GridDatabase.VERSION)
        manager.apply(restored)

        val reopened = GridDatabase.build(context)
        val after = TransactionRepository(reopened, clock, emptySet()).observeAll().first()
        assertThat(after.single().note).isEqualTo("Lunch")
        assertThat(settings.settings.first().themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(context.getDatabasePath(GridDatabase.NAME).resolveSibling("${GridDatabase.NAME}.bak").exists()).isTrue()
        reopened.close()
    }

    @Test fun garbageIsRejectedBeforeTouchingData() = runTest {
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("g.preferences_pb").also { it.delete() } }, Locale.FRANCE)
        val db = GridDatabase.build(context)
        val manager = BackupManager(context, db, settings, TransactionRepository(db, clock, emptySet()), clock)
        val result = runCatching { manager.inspect(ByteArrayInputStream("not a backup".toByteArray())) }
        assertThat((result.exceptionOrNull() as BackupException).reason).isEqualTo(BackupException.Reason.CORRUPT)
        // The live database is untouched and still usable.
        assertThat(db.categoryDao().all()).hasSize(22)
        db.close()
    }
}
