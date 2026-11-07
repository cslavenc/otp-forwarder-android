package ch.cslavenc.otpforwarder.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderFilterTest {
    private fun allowed(sender: String, vararg entries: String) = SenderFilter.isAllowed(sender, entries.toList())

    @Test
    fun `phone numbers match across formats`() {
        for (entry in listOf("+41790000000", "+41 79 000 00 00", "0041 79 000 00 00", "079 000 00 00", "0790000000", "079-000.00/00", "+41 (0)79 000 00 00")) {
            assertTrue(entry, allowed("+41790000000", entry))
        }
        assertTrue(allowed("0790000000", "+41 79 000 00 00"))
        assertTrue(allowed("0041790000000", "+41790000000"))
    }

    @Test
    fun `different or incomplete numbers do not match`() {
        assertFalse(allowed("+41790000001", "+41790000000"))
        assertFalse(allowed("41790000000", "+41790000000"))
        assertFalse(allowed("+790000000", "0790000000"))
        assertFalse(allowed("+41723", "0723"))
        assertFalse(allowed("+41790000000", "０７９０００００００"))
    }

    @Test
    fun `alphanumeric IDs match case- and space-insensitively but exactly`() {
        assertTrue(allowed("PostFinance", "POSTFINANCE"))
        assertTrue(allowed("PostFinance", "Post Finance"))
        assertFalse(allowed("PostFinance2", "PostFinance"))
        assertFalse(allowed("UBS", "UBS2"))
    }

    @Test
    fun `short codes match only themselves`() {
        assertTrue(allowed("723", "723"))
        assertFalse(allowed("7230", "723"))
    }

    @Test
    fun `empty allowlist, blank sender and blank entries never match`() {
        assertFalse(allowed("+41790000000"))
        assertFalse(allowed("", ""))
        assertFalse(allowed(" ", " ", "723"))
    }
}
