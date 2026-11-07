package ch.cslavenc.otpforwarder.core

/** One PDU-level part of an incoming SMS broadcast, in PDU order. */
class SmsPart(val sender: String, val body: String, val smscTimestampMs: Long)

/** What to do with the SMS of one sender in a broadcast. */
sealed interface SmsDecision {
    val sender: String

    data class Ignore(override val sender: String) : SmsDecision

    data class NoCode(override val sender: String) : SmsDecision

    data class TooOld(override val sender: String, val ageMinutes: Long) : SmsDecision

    data class Forward(override val sender: String, val code: String) : SmsDecision {
        override fun toString() = "Forward(sender=$sender)" // never print the code
    }

    companion object {
        /** SMS queued at the SMSC longer than this (phone was off, no signal) carry a code that is surely expired. */
        const val MAX_AGE_MS = 15 * 60_000L
        private const val MINUTE_MS = 60_000L

        /** Groups [parts] by sender, joins multipart bodies in order, then filters, age-checks and extracts. */
        fun decide(parts: List<SmsPart>, allowed: Collection<String>, nowMs: Long): List<SmsDecision> =
            parts.groupBy { it.sender }.map { (sender, group) -> decideOne(sender, group, allowed, nowMs) }

        private fun decideOne(sender: String, group: List<SmsPart>, allowed: Collection<String>, nowMs: Long): SmsDecision {
            if (!SenderFilter.isAllowed(sender, allowed)) return Ignore(sender)
            val smscTs = group.first().smscTimestampMs
            // 0 means the SMSC sent no usable timestamp; a future one is clock skew. Neither is stale.
            val age = nowMs - smscTs
            if (smscTs > 0 && age > MAX_AGE_MS) return TooOld(sender, age / MINUTE_MS)
            val code = OtpExtractor.extract(group.joinToString("") { it.body }) ?: return NoCode(sender)
            return Forward(sender, code)
        }
    }
}
