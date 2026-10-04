package com.grid.app.core.bank

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Reads the private key file Enable Banking hands out when an application is registered. */
object PemKeys {
    private val block = Regex("-----BEGIN ([A-Z ]+)-----([\\s\\S]+?)-----END \\1-----")

    fun parsePrivateKey(pem: String): PrivateKey {
        val match = block.find(pem) ?: throw IllegalArgumentException("Not an RSA private key")
        val der = runCatching { Base64.getMimeDecoder().decode(match.groupValues[2]) }.getOrElse { throw IllegalArgumentException("Not an RSA private key") }
        val pkcs8 = when (match.groupValues[1]) {
            "PRIVATE KEY" -> der
            "RSA PRIVATE KEY" -> wrapPkcs1(der)
            else -> throw IllegalArgumentException("Not an RSA private key")
        }
        return runCatching { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) }
            .getOrElse { throw IllegalArgumentException("Not an RSA private key", it) }
    }

    /** PKCS#8 = SEQUENCE { INTEGER 0, AlgorithmIdentifier(rsaEncryption, NULL), OCTET STRING(pkcs1) }. */
    private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val algorithm = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
        val octets = tlv(0x04, pkcs1)
        return tlv(0x30, version + algorithm + octets)
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(tag)
        val n = value.size
        when {
            n < 0x80 -> write(n)
            n < 0x100 -> { write(0x81); write(n) }
            n < 0x10000 -> { write(0x82); write(n shr 8); write(n and 0xff) }
            else -> { write(0x83); write(n shr 16); write((n shr 8) and 0xff); write(n and 0xff) }
        }
        write(value)
    }.toByteArray()
}

/** Enable Banking authenticates every API call with a short-lived JWT signed by the application's key. */
object EnableBankingJwt {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun sign(appId: String, key: PrivateKey, nowSeconds: Long, ttlSeconds: Long = 3600): String {
        val header = buildJsonObject { put("typ", "JWT"); put("alg", "RS256"); put("kid", appId) }.toString()
        val claims = buildJsonObject {
            put("iss", "enablebanking.com")
            put("aud", "api.enablebanking.com")
            put("iat", nowSeconds)
            put("exp", nowSeconds + ttlSeconds)
        }.toString()
        val signingInput = encoder.encodeToString(header.toByteArray()) + "." + encoder.encodeToString(claims.toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(signingInput.toByteArray())
            sign()
        }
        return signingInput + "." + encoder.encodeToString(signature)
    }
}
