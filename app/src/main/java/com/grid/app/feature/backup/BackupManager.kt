package com.grid.app.feature.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import com.grid.app.BuildConfig
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.time.AppClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * Creates and restores backup archives. A restore never touches the live database until the
 * incoming one has been fully extracted and verified; the previous file is kept as `grid.db.bak`.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: GridDatabase,
    private val settings: SettingsRepository,
    private val transactions: TransactionRepository,
    private val clock: AppClock,
) {
    private val workDir: File get() = File(context.cacheDir, "backup").apply { mkdirs() }

    /** Writes a full backup to [out] and returns its manifest. */
    suspend fun writeArchive(out: OutputStream): BackupManifest = withContext(Dispatchers.IO) {
        val snapshot = File(workDir, "snapshot.db").apply { delete() }
        snapshot(snapshot)
        try {
            val manifest = BackupManifest(
                formatVersion = BackupArchive.FORMAT_VERSION,
                schemaVersion = GridDatabase.VERSION,
                appVersion = BuildConfig.VERSION_NAME,
                createdAt = clock.millis(),
                device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                currency = settings.settings.first().currency,
                counts = counts(),
            )
            BackupArchive.write(out, snapshot, settings.exportJson(), manifest)
            manifest
        } finally {
            snapshot.delete()
        }
    }

    /** Reads and fully validates an archive without applying it. */
    suspend fun inspect(input: InputStream): RestoredArchive = withContext(Dispatchers.IO) {
        val restored = BackupArchive.read(input, workDir, maxSchema = GridDatabase.VERSION)
        verifyDatabase(restored.dbFile)
        restored
    }

    /**
     * Replaces the current data with [restored]. The caller must restart the app afterwards
     * ([restartApp]) because the open database is closed here.
     */
    suspend fun apply(restored: RestoredArchive) = withContext(Dispatchers.IO) {
        val live = context.getDatabasePath(GridDatabase.NAME)
        db.close()
        live.parentFile?.mkdirs()
        if (live.exists()) live.copyTo(File(live.path + ".bak"), overwrite = true)
        restored.dbFile.copyTo(live, overwrite = true)
        listOf("-wal", "-shm", "-journal").forEach { File(live.path + it).delete() }
        restored.dbFile.delete()
        settings.importJson(restored.settingsJson)
    }

    /** All transactions as CSV. */
    suspend fun csv(): String = CsvExport.render(transactions.observeAll().first(), clock.zone)

    /**
     * Relaunches the app so every component reopens the restored database: the launch request
     * reaches the system before this process exits, and the system starts a fresh one for it.
     * (An alarm-based restart can be deferred for minutes on recent Android versions.)
     */
    fun restartApp() {
        val component = context.packageManager.getLaunchIntentForPackage(context.packageName)!!.component!!
        context.startActivity(Intent.makeRestartActivityTask(component))
        exitProcess(0)
    }

    /**
     * A consistent copy of the live database. `VACUUM INTO` (SQLite 3.27+, Android 11+) copies safely
     * while the app keeps writing; older devices checkpoint the WAL into the main file and copy it.
     */
    private fun snapshot(target: File) {
        val sql = db.openHelper.writableDatabase
        val vacuumed = runCatching { sql.execSQL("VACUUM INTO ?", arrayOf<Any>(target.absolutePath)) }.isSuccess
        if (vacuumed && target.length() > 0) return
        target.delete()
        sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        context.getDatabasePath(GridDatabase.NAME).copyTo(target, overwrite = true)
    }

    private fun counts(): Map<String, Int> {
        val tables = listOf("transactions", "subscriptions", "pending_payments", "categories")
        return tables.associateWith { table ->
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }
    }

    /** The extracted file must be a healthy SQLite database of a schema this app can open. */
    private fun verifyDatabase(file: File) {
        val sqlite = runCatching { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }
            .getOrElse { throw BackupException(BackupException.Reason.CORRUPT, "Backup database can't be opened") }
        sqlite.use { database ->
            if (database.version > GridDatabase.VERSION) throw BackupException(BackupException.Reason.NEWER_VERSION, "Backup was made by a newer version of Grid")
            val ok = database.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
            val hasLedger = database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='transactions'", null).use { it.moveToFirst() }
            if (!ok || !hasLedger) throw BackupException(BackupException.Reason.CORRUPT, "Backup database is damaged")
        }
    }
}
