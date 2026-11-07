package ch.cslavenc.otpforwarder.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    /** No [nonce]: the provider picks a random IV, like the Keystore does in production. */
    private fun encryptCipher(nonce: ByteArray? = null) = Cipher.getInstance(Crypto.TRANSFORMATION).apply {
        if (nonce == null) init(Cipher.ENCRYPT_MODE, key) else init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
    }

    private fun open(topic: String, body: String): ByteArray {
        val raw = Base64.getUrlDecoder().decode(body)
        return Cipher.getInstance(Crypto.TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, Crypto.NONCE_LEN))
            updateAAD(Crypto.aad(topic))
            doFinal(raw, Crypto.NONCE_LEN, raw.size - Crypto.NONCE_LEN)
        }
    }

    @Test
    fun `seal matches test vector V1`() {
        val body = Crypto.seal(encryptCipher(ByteArray(12) { it.toByte() }), TOPIC, V1_PLAINTEXT.toByteArray())
        assertEquals(V1_BODY, body)
        assertEquals(KEY_B64, Crypto.b64(key.encoded))
    }

    @Test
    fun `random IV round-trips and differs per seal`() {
        val plaintext = V1_PLAINTEXT.toByteArray()
        val a = Crypto.seal(encryptCipher(), TOPIC, plaintext)
        val b = Crypto.seal(encryptCipher(), TOPIC, plaintext)
        assertNotEquals(a.take(16), b.take(16))
        assertArrayEquals(plaintext, open(TOPIC, a))
        assertArrayEquals(plaintext, open(TOPIC, b))
    }

    @Test
    fun `AAD binds the body to its topic`() {
        val body = Crypto.seal(encryptCipher(), TOPIC, V1_PLAINTEXT.toByteArray())
        assertThrows(AEADBadTagException::class.java) { open("BBBBBBBBBBBBBBBBBBBBBB", body) }
    }

    @Test
    fun `body is capped at 1024 chars`() {
        assertEquals(1024, Crypto.seal(encryptCipher(), TOPIC, ByteArray(740)).length)
        assertThrows(IllegalStateException::class.java) { Crypto.seal(encryptCipher(), TOPIC, ByteArray(741)) }
    }

    @Test
    fun `b64Decode accepts padding and rejects non-url alphabet`() {
        assertEquals(32, Crypto.b64Decode("$KEY_B64=")?.size)
        assertEquals(32, Crypto.b64Decode(KEY_B64)?.size)
        assertNull(Crypto.b64Decode("AAEC+wQF"))
        assertNull(Crypto.b64Decode("AAEC/wQF"))
    }

    companion object {
        const val TOPIC = "AAAAAAAAAAAAAAAAAAAAAA"
        const val KEY_B64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val V1_PLAINTEXT =
            """{"v":1,"id":"00000000-0000-4000-8000-000000000000","code":"123456","sender":"+41000000000","ts":1700000000000}"""
        const val V1_BODY =
            "AAECAwQFBgcICQoLPCCgOf_U7jnkJbWxk9lIXbPmtwTAVm9MCFfIsS1ZMJ85IJ7MgvEiqESUT924txgI3ntMrzm5x78drQgoKtDB28Ye6ligtEgFeSaIVM2kOtkIuvlSRlNjWM3fsu4Mg5u3-s9p1pRx-CquLA6CeAhsRUgSYr9_WFD7G9Hj2068"
    }
}
