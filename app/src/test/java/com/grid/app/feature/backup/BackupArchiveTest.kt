package com.grid.app.feature.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {

    @get:Rule val tmp = TemporaryFolder()

    private val manifest = BackupManifest(
        formatVersion = 1, schemaVersion = 1, appVersion = "2.0.0", createdAt = 1_791_000_000_000,
        device = "Pixel 9", currency = "EUR", counts = mapOf("transactions" to 42, "subscriptions" to 3),
    )

    private fun archiveBytes(m: BackupManifest = manifest, db: ByteArray = "SQLite format 3\u0000data".toByteArray()): ByteArray {
        val dbFile = tmp.newFile().apply { writeBytes(db) }
        return ByteArrayOutputStream().also { BackupArchive.write(it, dbFile, """{"currency":"EUR"}""", m) }.toByteArray()
    }

    @Test fun roundTrip() {
        val restored = BackupArchive.read(ByteArrayInputStream(archiveBytes()), tmp.newFolder(), maxSchema = 1)
        assertThat(restored.manifest).isEqualTo(manifest)
        assertThat(restored.settingsJson).isEqualTo("""{"currency":"EUR"}""")
        assertThat(restored.dbFile.readText()).startsWith("SQLite format 3")
    }

    @Test fun newerSchemaIsRejected() {
        val e = assertThrows(BackupException::class.java) {
            BackupArchive.read(ByteArrayInputStream(archiveBytes(manifest.copy(schemaVersion = 2))), tmp.newFolder(), maxSchema = 1)
        }
        assertThat(e.reason).isEqualTo(BackupException.Reason.NEWER_VERSION)
    }

    @Test fun unknownFormatIsRejected() {
        val e = assertThrows(BackupException::class.java) {
            BackupArchive.read(ByteArrayInputStream(archiveBytes(manifest.copy(formatVersion = 9))), tmp.newFolder(), maxSchema = 1)
        }
        assertThat(e.reason).isEqualTo(BackupException.Reason.NEWER_VERSION)
    }

    @Test fun missingDatabaseIsRejected() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(BackupArchive.encode(manifest).toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        val e = assertThrows(BackupException::class.java) { BackupArchive.read(ByteArrayInputStream(bytes), tmp.newFolder(), maxSchema = 1) }
        assertThat(e.reason).isEqualTo(BackupException.Reason.CORRUPT)
    }

    @Test fun notAZipIsRejected() {
        val e = assertThrows(BackupException::class.java) {
            BackupArchive.read(ByteArrayInputStream("hello".toByteArray()), tmp.newFolder(), maxSchema = 1)
        }
        assertThat(e.reason).isEqualTo(BackupException.Reason.CORRUPT)
    }

    @Test fun pathTraversalEntriesAreIgnored() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("../../evil.txt")); zip.write("x".toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        val dir = tmp.newFolder()
        assertThrows(BackupException::class.java) { BackupArchive.read(ByteArrayInputStream(bytes), dir, maxSchema = 1) }
        assertThat(dir.parentFile!!.resolve("evil.txt").exists()).isFalse()
    }
}
