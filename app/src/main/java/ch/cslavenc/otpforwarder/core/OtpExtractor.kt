package ch.cslavenc.otpforwarder.core

/**
 * Finds the one-time code in an SMS body. The test corpus (OtpExtractorTest) is the spec: change the rules only
 * together with a new corpus case. Letters-only codes and codes longer than 10 characters are not supported.
 */
object OtpExtractor {
    private val KEYWORD = Regex(
        "code|codice|passw|kennwort|mot de passe|token|verif|bestätig|freigabe|confirm|conferm|" +
            "sécurité|sicherheit|sicurezza|\\b(?:m?tan|otp|pin)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val PLAIN = Regex("(?<![\\p{L}\\p{N}])[A-Za-z0-9]{4,10}(?![\\p{L}\\p{N}])")

    /** "482 913" or "123-456", but not a slice of a longer group such as a phone number. */
    private val GROUPED = Regex("(?<![\\p{L}\\p{N}]|[0-9][ -])[0-9]{3}[ -][0-9]{3}(?![\\p{L}\\p{N}]|[ -][0-9])")

    /** Part of an amount, date, time or decimal such as 1'250, 08.11.2026 or 14:30. */
    private val NUMBER_BEFORE = Regex("[0-9][.,'’:/]$")
    private val NUMBER_AFTER = Regex("^[.,'’:/][0-9]")
    private val CURRENCY_BEFORE = Regex("(?:CHF|EUR|USD|S?Fr\\.|[€\$£]) ?$")
    private val CODE = Regex("[A-Za-z0-9]{4,10}")

    private class Candidate(val start: Int, val end: Int, val code: String)

    fun extract(body: String): String? {
        val keywords = KEYWORD.findAll(body).toList()
        if (keywords.isEmpty()) return null
        val candidates = candidates(body)
        // First candidate after the last keyword that has one after it, else the last one before the first keyword.
        val pick = keywords.asReversed().firstNotNullOfOrNull { kw -> candidates.firstOrNull { it.start > kw.range.last } }
            ?: candidates.lastOrNull { it.end <= keywords.first().range.first }
        return pick?.code?.takeIf { CODE.matches(it) }
    }

    private fun candidates(body: String): List<Candidate> {
        val plain = PLAIN.findAll(body).filter { m -> m.value.any { it in '0'..'9' } }
            .map { Candidate(it.range.first, it.range.last + 1, it.value) }
        val grouped = GROUPED.findAll(body)
            .map { Candidate(it.range.first, it.range.last + 1, it.value.filter { c -> c in '0'..'9' }) }
        return (plain + grouped)
            .filterNot { isPartOfNumber(body, it) }
            .sortedBy { it.start }
            .toList()
    }

    private fun isPartOfNumber(body: String, c: Candidate): Boolean {
        val before = body.substring(0, c.start)
        val after = body.substring(c.end)
        return NUMBER_BEFORE.containsMatchIn(before) ||
            NUMBER_AFTER.containsMatchIn(after) ||
            CURRENCY_BEFORE.containsMatchIn(before)
    }
}
