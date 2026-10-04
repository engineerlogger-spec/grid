package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BankKeyStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Stands in for the AndroidKeyStore, which the JVM doesn't have. */
    private object XorBox : SecretBox {
        override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    @Test fun savesAndLoadsCredentials() {
        val file = tmp.root.resolve("bank.key")
        val store = BankKeyStore(file, XorBox)
        assertThat(store.hasCredentials()).isFalse()
        store.save(BankCredentials("app-1", "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----"))
        assertThat(store.hasCredentials()).isTrue()
        assertThat(file.readText()).doesNotContain("app-1")
        assertThat(BankKeyStore(file, XorBox).load()).isEqualTo(BankCredentials("app-1", "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----"))
    }

    @Test fun corruptFileLoadsAsNothing() {
        val file = tmp.root.resolve("bank.key").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertThat(BankKeyStore(file, XorBox).load()).isNull()
    }

    @Test fun clearRemovesTheKey() {
        val file = tmp.root.resolve("bank.key")
        val store = BankKeyStore(file, XorBox)
        store.save(BankCredentials("a", "b"))
        store.clear()
        assertThat(file.exists()).isFalse()
        assertThat(store.load()).isNull()
    }
}
