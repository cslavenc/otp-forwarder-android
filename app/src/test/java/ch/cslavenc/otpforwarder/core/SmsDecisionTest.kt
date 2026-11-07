package ch.cslavenc.otpforwarder.core

import ch.cslavenc.otpforwarder.core.SmsDecision.Forward
import ch.cslavenc.otpforwarder.core.SmsDecision.Ignore
import ch.cslavenc.otpforwarder.core.SmsDecision.NoCode
import ch.cslavenc.otpforwarder.core.SmsDecision.TooOld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SmsDecisionTest {
    private fun decide(vararg parts: SmsPart) = SmsDecision.decide(parts.toList(), ALLOWED, NOW)

    @Test
    fun `multipart bodies are joined in order`() {
        val decisions = decide(SmsPart(BANK, "Ihr Code lautet 12", NOW), SmsPart(BANK, "3456", NOW))
        assertEquals(listOf(Forward(BANK, "123456")), decisions)
    }

    @Test
    fun `two senders in one broadcast are decided separately`() {
        val decisions = decide(
            SmsPart(BANK, "Code: 4821", NOW),
            SmsPart(STRANGER, "Code: 9999", NOW),
            SmsPart(BANK, " gültig 5 Min.", NOW),
        )
        assertEquals(listOf(Forward(BANK, "4821"), Ignore(STRANGER)), decisions)
    }

    @Test
    fun `interleaved multipart SMS from two allowed senders are joined per sender`() {
        val decisions = SmsDecision.decide(
            listOf(
                SmsPart(BANK, "Ihr Code lautet 48", NOW),
                SmsPart("PostFinance", "TAN: 77", NOW),
                SmsPart(BANK, "2913", NOW),
                SmsPart("PostFinance", "12", NOW),
            ),
            ALLOWED + "PostFinance",
            NOW,
        )
        assertEquals(listOf(Forward(BANK, "482913"), Forward("PostFinance", "7712")), decisions)
    }

    @Test
    fun `sender not allowed is ignored`() {
        assertEquals(listOf(Ignore(STRANGER)), decide(SmsPart(STRANGER, "Code: 4821", NOW)))
        assertEquals(listOf(Ignore(BANK)), SmsDecision.decide(listOf(SmsPart(BANK, "Code: 4821", NOW)), emptySet(), NOW))
    }

    @Test
    fun `stale SMS is too old, fresh or unknown timestamps are not`() {
        assertEquals(listOf(TooOld(BANK, 16)), decide(SmsPart(BANK, "Code: 4821", NOW - 16 * MINUTE)))
        assertEquals(listOf(Forward(BANK, "4821")), decide(SmsPart(BANK, "Code: 4821", NOW - 15 * MINUTE)))
        assertEquals(listOf(Forward(BANK, "4821")), decide(SmsPart(BANK, "Code: 4821", NOW - 14 * MINUTE)))
        assertEquals(listOf(Forward(BANK, "4821")), decide(SmsPart(BANK, "Code: 4821", 0)))
        assertEquals(listOf(Forward(BANK, "4821")), decide(SmsPart(BANK, "Code: 4821", NOW + 5 * MINUTE)))
    }

    @Test
    fun `allowed SMS without a code is NoCode`() {
        assertEquals(listOf(NoCode(BANK)), decide(SmsPart(BANK, "Ihre Zahlung wurde ausgeführt", NOW)))
    }

    @Test
    fun `Forward never prints the code`() {
        assertFalse(Forward(BANK, "482913").toString().contains("482913"))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val MINUTE = 60_000L
        const val BANK = "+41790000000"
        const val STRANGER = "+41790000001"
        val ALLOWED = setOf("079 000 00 00")
    }
}
