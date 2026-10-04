package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class BankCryptoTest {

    private val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun pem(type: String, der: ByteArray) =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

    @Test fun parsesPkcs8Pem() {
        val key = PemKeys.parsePrivateKey(pem("PRIVATE KEY", pair.private.encoded))
        assertThat(key.encoded).isEqualTo(pair.private.encoded)
    }

    @Test fun parsesPkcs1PemAsTheSameKey() {
        // A 2048-bit PKCS#8 encoding is a fixed 26-byte header followed by the PKCS#1 key.
        val pkcs1 = pair.private.encoded.copyOfRange(26, pair.private.encoded.size)
        val key = PemKeys.parsePrivateKey(pem("RSA PRIVATE KEY", pkcs1))
        assertThat(key.encoded).isEqualTo(pair.private.encoded)
    }

    @Test fun rejectsAnythingElse() {
        assertThrows(IllegalArgumentException::class.java) { PemKeys.parsePrivateKey("hello") }
        assertThrows(IllegalArgumentException::class.java) { PemKeys.parsePrivateKey(pem("CERTIFICATE", byteArrayOf(1, 2, 3))) }
    }

    @Test fun jwtIsSignedWithTheKeyAndCarriesEnableBankingClaims() {
        val jwt = EnableBankingJwt.sign("app-123", pair.private, nowSeconds = 1_790_000_000)
        val (header, claims, signature) = jwt.split('.')
        val decoder = Base64.getUrlDecoder()

        val h = Json.parseToJsonElement(decoder.decode(header).decodeToString()).jsonObject
        assertThat(h["alg"]!!.jsonPrimitive.content).isEqualTo("RS256")
        assertThat(h["kid"]!!.jsonPrimitive.content).isEqualTo("app-123")
        val c = Json.parseToJsonElement(decoder.decode(claims).decodeToString()).jsonObject
        assertThat(c["iss"]!!.jsonPrimitive.content).isEqualTo("enablebanking.com")
        assertThat(c["aud"]!!.jsonPrimitive.content).isEqualTo("api.enablebanking.com")
        assertThat(c["exp"]!!.jsonPrimitive.long - c["iat"]!!.jsonPrimitive.long).isEqualTo(3600)
        assertThat(jwt).doesNotContain("=")

        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(pair.public)
            update("$header.$claims".toByteArray())
        }
        assertThat(verifier.verify(decoder.decode(signature))).isTrue()
    }
}
