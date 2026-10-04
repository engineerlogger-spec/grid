package com.grid.app.core.bank

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets at rest. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-256-GCM with a key that never leaves the AndroidKeyStore. Output: 12-byte IV + ciphertext. */
class KeystoreSecretBox(private val alias: String = "grid.bank") : SecretBox {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_SIZE))
        }
        return cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/** The Enable Banking application id and its private key (PEM). */
@Serializable
data class BankCredentials(val appId: String, val privateKeyPem: String)

/**
 * Keeps the bank credentials encrypted in `noBackupFilesDir`: they are never part of Grid's backups or
 * Android's, so a restored phone has to be connected again instead of carrying bank access around.
 */
class BankKeyStore(private val file: File, private val box: SecretBox) {
    private val json = Json { ignoreUnknownKeys = true }

    fun hasCredentials(): Boolean = file.exists()

    fun save(credentials: BankCredentials) {
        file.parentFile?.mkdirs()
        file.writeBytes(box.seal(json.encodeToString(BankCredentials.serializer(), credentials).toByteArray()))
    }

    fun load(): BankCredentials? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(BankCredentials.serializer(), box.open(file.readBytes()).decodeToString()) }.getOrNull()
    }

    fun clear() {
        file.delete()
    }
}
