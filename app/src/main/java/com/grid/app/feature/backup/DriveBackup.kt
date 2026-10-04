package com.grid.app.feature.backup

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.time.AppClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

sealed interface DriveAuthResult {
    data class Token(val accessToken: String) : DriveAuthResult
    /** The user must pick an account / grant access once: launch this from an Activity. */
    data class NeedsConsent(val intentSender: IntentSender) : DriveAuthResult
    /** This build has no OAuth client for its package + signing key (see docs/GOOGLE_DRIVE_SETUP.md). */
    data object NotConfigured : DriveAuthResult
    data class Failed(val message: String) : DriveAuthResult
}

/** Google Identity authorization for the `drive.appdata` scope (non-sensitive, no verification needed). */
@Singleton
class DriveAuth @Inject constructor(@ApplicationContext private val context: Context) {

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_APPDATA)))
        .build()

    suspend fun authorize(): DriveAuthResult = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { result ->
                val token = result.accessToken
                val pending = result.pendingIntent
                cont.resume(
                    when {
                        result.hasResolution() && pending != null -> DriveAuthResult.NeedsConsent(pending.intentSender)
                        token != null -> DriveAuthResult.Token(token)
                        else -> DriveAuthResult.Failed("No access token")
                    },
                )
            }
            .addOnFailureListener { e -> cont.resume(mapFailure(e)) }
    }

    /** Completes a consent flow started with [DriveAuthResult.NeedsConsent]. */
    fun fromConsentResult(data: Intent?): DriveAuthResult = try {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        result.accessToken?.let { DriveAuthResult.Token(it) } ?: DriveAuthResult.Failed("No access token")
    } catch (e: Exception) {
        mapFailure(e)
    }

    private fun mapFailure(e: Exception): DriveAuthResult = when {
        e is ApiException && e.statusCode == CommonStatusCodes.DEVELOPER_ERROR -> DriveAuthResult.NotConfigured
        e is ApiException && e.statusCode == CommonStatusCodes.CANCELED -> DriveAuthResult.Failed("Cancelled")
        else -> DriveAuthResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    companion object {
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    }
}

/** Backup/restore against Drive's hidden app folder. Keeps the [KEEP] newest backups. */
@Singleton
class DriveBackup @Inject constructor(
    private val client: DriveClient,
    private val manager: BackupManager,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) {
    /** Uploads a fresh backup; returns its size in bytes. */
    suspend fun backupNow(token: String): Long {
        val bytes = ByteArrayOutputStream().also { manager.writeArchive(it) }.toByteArray()
        val now = clock.millis()
        client.upload(
            token, "$PREFIX$now.zip", bytes,
            mapOf("app" to "grid", "schema" to GridDatabase.VERSION.toString(), "createdAt" to now.toString()),
        )
        backups(token).drop(KEEP).forEach { runCatching { client.delete(token, it.id) } }
        settings.recordBackup(now, bytes.size.toLong())
        settings.setBackupConnected(runCatching { client.userEmail(token) }.getOrNull())
        return bytes.size.toLong()
    }

    /** Grid backups in the app folder, newest first. */
    suspend fun backups(token: String): List<DriveFile> = client.list(token).filter { it.name.startsWith(PREFIX) }

    /** Downloads and validates the newest backup (nothing is applied yet). */
    suspend fun fetchLatest(token: String): RestoredArchive {
        val latest = backups(token).firstOrNull() ?: throw BackupException(BackupException.Reason.NO_BACKUP, "No backup found in Google Drive")
        return manager.inspect(client.download(token, latest.id).inputStream())
    }

    companion object {
        const val PREFIX = "grid-backup-"
        const val KEEP = 2
    }
}
