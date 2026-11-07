package ch.cslavenc.otpforwarder.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadTest {
    @Test
    fun `encode matches the V1 plaintext byte for byte`() {
        val bytes = Payload.encode("00000000-0000-4000-8000-000000000000", "123456", "+41000000000", 1700000000000)
        assertEquals(CryptoTest.V1_PLAINTEXT, String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `invalid code or id is rejected without echoing the code`() {
        for (code in listOf("123", "12345678901", "12 34", "١٢٣٤٥٦")) {
            val e = assertThrows(IllegalArgumentException::class.java) { Payload.encode(Payload.newId(), code, "", 0) }
            assertFalse(e.message.orEmpty().contains(code))
        }
        assertThrows(IllegalArgumentException::class.java) { Payload.encode("too-short", "123456", "", 0) }
    }

    @Test
    fun `cleanSender drops control characters and cuts at 64 code points`() {
        assertEquals("Zürich+41", Payload.cleanSender("Zü\nrich\u0000\u0085\u007f+41"))
        val cut = Payload.cleanSender("\uD83D\uDE00".repeat(70))
        assertEquals(64, cut.codePointCount(0, cut.length))
        assertEquals("\uD83D\uDE00".repeat(64), cut)
    }

    @Test
    fun `worst-case sender stays within 512 bytes and parses back`() {
        val sender = "\u2028".repeat(Payload.MAX_SENDER_CHARS)
        val bytes = Payload.encode(Payload.newId(), "ABCDEFGH12", sender, Long.MAX_VALUE)
        assertTrue(bytes.size <= Payload.MAX_BYTES)
        assertEquals(sender, JSONObject(String(bytes, Charsets.UTF_8)).getString("sender"))
    }
}
