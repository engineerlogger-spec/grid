package com.grid.app.feature.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Describes a backup file; checked before anything is restored. */
@Serializable
data class BackupManifest(
    val formatVersion: Int,
    /** Room schema version of the database inside. A newer app can restore older schemas (migrations). */
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val device: String,
    val currency: String,
    val counts: Map<String, Int> = emptyMap(),
)

class BackupException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { CORRUPT, NEWER_VERSION, NOT_CONFIGURED, NO_BACKUP, AUTH, NETWORK }
}

data class RestoredArchive(val manifest: BackupManifest, val dbFile: File, val settingsJson: String)

/**
 * The backup file format: a zip with `manifest.json`, `grid.db` (a consistent SQLite snapshot) and
 * `settings.json`. The same format is used for Google Drive and for local backup files.
 */
object BackupArchive {
    const val FORMAT_VERSION = 1
    private const val MANIFEST = "manifest.json"
    private const val DATABASE = "grid.db"
    private const val SETTINGS = "settings.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(manifest: BackupManifest): String = json.encodeToString(BackupManifest.serializer(), manifest)

    fun write(out: OutputStream, dbSnapshot: File, settingsJson: String, manifest: BackupManifest) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(encode(manifest).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(DATABASE)); dbSnapshot.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            zip.putNextEntry(ZipEntry(SETTINGS)); zip.write(settingsJson.toByteArray()); zip.closeEntry()
        }
    }

    /**
     * Extracts and validates an archive into [workDir]. Only the three known entries are written,
     * by fixed name, so a crafted entry path can never escape the directory.
     */
    fun read(input: InputStream, workDir: File, maxSchema: Int): RestoredArchive {
        workDir.mkdirs()
        val dbFile = File(workDir, "restore-$DATABASE")
        var manifest: BackupManifest? = null
        var settings: String? = null
        var hasDb = false
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        MANIFEST -> manifest = runCatching { json.decodeFromString(BackupManifest.serializer(), zip.readBytes().decodeToString()) }
                            .getOrElse { throw BackupException(BackupException.Reason.CORRUPT, "Unreadable manifest") }
                        DATABASE -> { dbFile.outputStream().use { zip.copyTo(it) }; hasDb = true }
                        SETTINGS -> settings = zip.readBytes().decodeToString()
                        else -> Unit // ignore anything unexpected (including path-traversal names)
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: ZipException) {
            throw BackupException(BackupException.Reason.CORRUPT, "Not a Grid backup")
        }
        val m = manifest ?: throw BackupException(BackupException.Reason.CORRUPT, "Not a Grid backup")
        if (m.formatVersion > FORMAT_VERSION || m.schemaVersion > maxSchema) {
            throw BackupException(BackupException.Reason.NEWER_VERSION, "Backup was made by a newer version of Grid")
        }
        if (!hasDb || dbFile.length() == 0L) throw BackupException(BackupException.Reason.CORRUPT, "Backup has no data")
        return RestoredArchive(m, dbFile, settings ?: "{}")
    }
}
