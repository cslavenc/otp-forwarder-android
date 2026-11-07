package ch.cslavenc.otpforwarder.core

import java.util.Locale

/** Allowed-sender matching. Entries are stored as the user typed them and normalized here. */
object SenderFilter {
    private const val SEPARATORS = "()./-"
    private val PHONE = Regex("\\+?[0-9]+")

    /** Trunk "0" plus at least 8 digits, e.g. Swiss 079 123 45 67. */
    private val NATIONAL = Regex("0[1-9][0-9]{7,}")

    /** Drops a "(0)" trunk hint, spaces and phone separators; "00" prefix → "+"; alphanumeric IDs are lower-cased. */
    fun normalize(raw: String): String {
        val s = raw.replace("(0)", "").filterNot { it.isWhitespace() || it in SEPARATORS }
        if (!PHONE.matches(s)) return s.lowercase(Locale.ROOT)
        return if (s.startsWith("00")) "+" + s.drop(2) else s
    }

    /** False for an empty allowlist or a blank sender. */
    fun isAllowed(sender: String, allowed: Collection<String>): Boolean {
        val s = normalize(sender)
        return s.isNotEmpty() && allowed.any { matches(s, normalize(it)) }
    }

    private fun matches(a: String, b: String) = a == b || bridges(a, b) || bridges(b, a)

    /** "+41791234567" equals national "0791234567" in any country (accepted: sender IDs can be spoofed anyway). */
    private fun bridges(international: String, national: String) =
        international.startsWith("+") &&
            PHONE.matches(international) &&
            NATIONAL.matches(national) &&
            international.length > national.length &&
            international.endsWith(national.drop(1))
}
