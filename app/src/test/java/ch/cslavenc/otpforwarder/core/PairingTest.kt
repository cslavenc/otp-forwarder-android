package ch.cslavenc.otpforwarder.core

import ch.cslavenc.otpforwarder.core.CryptoTest.Companion.KEY_B64
import ch.cslavenc.otpforwarder.core.CryptoTest.Companion.TOPIC
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private fun json(relay: String = RELAY, topic: String = TOPIC, key: String = KEY_B64) =
        """{"v":1,"relay":"$relay","topic":"$topic","key":"$key"}"""

    private fun reason(json: String): String? = parsePairing(json).exceptionOrNull()?.message

    private fun relayOf(relay: String) = parsePairing(json(relay = relay)).getOrThrow().relay

    @Test
    fun `valid pairing parses`() {
        val p = parsePairing(json()).getOrThrow()
        assertEquals(RELAY, p.relay)
        assertEquals(TOPIC, p.topic)
        assertArrayEquals(ByteArray(32) { it.toByte() }, p.key)
        assertFalse(p.toString().contains(KEY_B64))
    }

    @Test
    fun `relay is normalized`() {
        assertEquals("https://ntfy.sh", relayOf("https://ntfy.sh/"))
        assertEquals("https://ntfy.sh", relayOf("HTTPS://NTFY.SH"))
        assertEquals("https://ntfy.sh", relayOf("https://ntfy.sh:443"))
        assertEquals("https://relay.example:8443", relayOf("https://relay.example:8443"))
    }

    @Test
    fun `padded key, unknown fields and trailing whitespace are accepted`() {
        assertTrue(parsePairing(json(key = "$KEY_B64=")).isSuccess)
        assertTrue(parsePairing("""{"v":1,"relay":"$RELAY","topic":"$TOPIC","key":"$KEY_B64","note":"x"}""").isSuccess)
        assertTrue(parsePairing(json() + "\n ").isSuccess)
        assertTrue(parsePairing(json(topic = "A".repeat(16))).isSuccess)
        assertTrue(parsePairing(json(topic = "A".repeat(64))).isSuccess)
    }

    @Test
    fun `bad relays are rejected`() {
        val cases = mapOf(
            "" to "relay must be an absolute URL",
            "ntfy.sh" to "relay must be an absolute URL",
            "http://ntfy.sh" to "relay must be an https URL",
            "ftp://ntfy.sh" to "relay must be an https URL",
            "https://user:pw@ntfy.sh" to "relay must not contain credentials",
            "https://user@ntfy.sh" to "relay must not contain credentials",
            "https://ntfy.sh/topic" to ORIGIN_ONLY,
            "https://ntfy.sh//" to ORIGIN_ONLY,
            "https://ntfy.sh/?a=b" to ORIGIN_ONLY,
            "https://ntfy.sh/?" to ORIGIN_ONLY,
            "https://ntfy.sh?" to ORIGIN_ONLY,
            "https://ntfy.sh/#frag" to ORIGIN_ONLY,
            "https://ntfy.sh#" to ORIGIN_ONLY,
            "https://ntfy.sh:65536" to "relay port is invalid",
            "https://ntfy.sh:99999" to "relay port is invalid",
        )
        for ((relay, expected) in cases) assertEquals(relay, expected, reason(json(relay = relay)))
    }

    @Test
    fun `bad topics and keys are rejected without echoing the key`() {
        for (topic in listOf("A".repeat(15), "A".repeat(65), "AAAAAAAAAAAAAAAAAAAAA=")) {
            assertEquals("topic must be 16-64 base64url characters", reason(json(topic = topic)))
        }
        for (key in listOf(KEY_B64.take(42), "", "AAEC+wQF", "AAEC/wQF", "${KEY_B64}AAAA")) {
            assertEquals("key must be 32 bytes of base64url", reason(json(key = key)))
        }
    }

    @Test
    fun `bad shape and version are rejected`() {
        assertEquals("unsupported version", reason(json().replace("\"v\":1", "\"v\":2")))
        val malformed = listOf(
            "not json",
            "",
            "[]",
            json() + " x",
            json() + "{}",
            json().replace("\"v\":1", "\"v\":\"1\""),
            json().replace("\"v\":1", "\"v\":1.0"),
            json().replace("\"v\":1", "\"v\":1.5"),
            json().replace("\"v\":1", "\"v\":300"),
            json().replace("\"v\":1", "\"v\":-1"),
            json().replace("\"v\":1", "\"v\":null"),
            json().replace("\"v\":1,", ""),
            """{"v":1,"relay":"$RELAY","topic":"$TOPIC"}""",
            """{"v":1,"relay":"$RELAY","topic":"$TOPIC","key":123}""",
            """{"v":1,"relay":"$RELAY","topic":null,"key":"$KEY_B64"}""",
            """{"v":1,"relay":{},"topic":"$TOPIC","key":"$KEY_B64"}""",
        )
        for (input in malformed) assertEquals(input, "malformed JSON", reason(input))
    }

    private companion object {
        const val RELAY = "https://ntfy.sh"
        const val ORIGIN_ONLY = "relay must be an origin without path, query or fragment"
    }
}
