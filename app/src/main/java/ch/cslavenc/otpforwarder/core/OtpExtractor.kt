package ch.cslavenc.otpforwarder.core

/**
 * Finds the one-time code in an SMS body. The test corpus (OtpExtractorTest) is the spec: change the rules only
 * together with a new corpus case. Letters-only codes and codes longer than 10 characters are not supported.
 */
object OtpExtractor {
    private val KEYWORD = Regex(
        "code|codice|passw|kennwort|mot de passe|token|verif|bestätig|freigabe|confirm|conferm|" +
            "sécurité|sicherheit|sicurezza|vérif|\\b(?:(?:sms|m)?tan|otp|pin)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Numbers that are never the code; blanked out (same length, so offsets stay valid) before the search. */
    private val MASKS = listOf(
        Regex("\\b[A-Z]{2}[0-9]{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,3})?\\b"), // IBAN
        Regex("(?:\\+|\\b00)[0-9](?:[ ./()-]{0,2}[0-9]){6,14}"), // international phone number
        Regex("\\b0[0-9](?:[ ./-]?[0-9]){8}\\b"), // 10-digit national phone number, e.g. 0800 123 456
        Regex("[0-9][0-9'’.,]* ?(?:CHF|EUR|USD|GBP|[€\$£])(?![A-Za-z])"), // amount followed by its currency
        Regex("(?:[*•]{2,}|[xX]{4,})[0-9]{2,6}"), // masked card number
        Regex("(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{11}\\s*$"), // SMS Retriever app hash
    )
    private val UNICODE_SPACE = Regex("\\p{Z}")
    private val PLAIN = Regex("(?<![\\p{L}\\p{N}])[A-Za-z0-9]{4,10}(?![\\p{L}\\p{N}])")

    /** "482 913" or "123-456", but not a slice of a longer group such as a phone number. */
    private val GROUPED = Regex("(?<![\\p{L}\\p{N}]|[0-9][ -])[0-9]{3}[ -][0-9]{3}(?![\\p{L}\\p{N}]|[ -][0-9])")

    /** Part of an amount, date, time or decimal such as 1'250, 08.11.2026 or 14:30. */
    private val NUMBER_BEFORE = Regex("[0-9][.,'’:/]$")
    private val NUMBER_AFTER = Regex("^[.,'’:/][0-9]")
    private val CURRENCY_BEFORE = Regex("(?:CHF|EUR|USD|S?Fr\\.|[€\$£]) ?$")
    private val CODE = Regex("[A-Za-z0-9]{4,10}")

    private class Candidate(val start: Int, val end: Int, val code: String)

    /** Bounds the regex work in the synchronous SMS receiver; real OTP SMS are far shorter. */
    const val MAX_BODY_CHARS = 2_000

    /** Null for bodies over [MAX_BODY_CHARS]: a cut-off text could end mid-number and yield a wrong code. */
    fun extract(sms: String): String? {
        if (sms.length > MAX_BODY_CHARS) return null
        val body = MASKS.fold(UNICODE_SPACE.replace(sms, " ")) { text, mask ->
            mask.replace(text) { " ".repeat(it.value.length) }
        }
        val keywords = KEYWORD.findAll(body).toList()
        if (keywords.isEmpty()) return null
        val candidates = candidates(body)
        // First candidate after the first keyword that has one after it (a repeated "code" in a disclaimer must not
        // pull in a later number), else the last candidate before the first keyword.
        val pick = keywords.firstNotNullOfOrNull { kw -> candidates.firstOrNull { it.start > kw.range.last } }
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
        // Short windows suffice (longest pattern: "SFr. ") and avoid copying the whole body per candidate.
        val before = body.substring(maxOf(0, c.start - NEIGHBOUR_CHARS), c.start)
        val after = body.substring(c.end, minOf(body.length, c.end + NEIGHBOUR_CHARS))
        return NUMBER_BEFORE.containsMatchIn(before) ||
            NUMBER_AFTER.containsMatchIn(after) ||
            CURRENCY_BEFORE.containsMatchIn(before)
    }

    private const val NEIGHBOUR_CHARS = 6
}
