package ch.cslavenc.otpforwarder.core

import java.io.IOException

/** Publishing policy: retry the identical body until shortly before the desktop's 120 s freshness limit. */
object Delivery {
    /** Measured from the payload ts; the 10 s margin covers relay latency and clock skew. */
    const val SEND_DEADLINE_MS = 110_000L

    /** The desktop drops messages older than this, so a later 2xx is not a delivery. */
    const val DESKTOP_MAX_AGE_MS = 120_000L
    const val MAX_ATTEMPT_TIMEOUT_MS = 10_000L
    const val FIRST_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 15_000L

    enum class Outcome { SENT, RETRY, FAIL }

    sealed interface Report {
        data object Sent : Report

        /** [detail] is "http <status>" or "expired", optionally with the last retryable error. */
        data class Failed(val detail: String) : Report
    }

    fun classify(httpStatus: Int): Outcome = when (httpStatus) {
        in 200..299 -> Outcome.SENT
        -1, 408, 429, in 500..599 -> Outcome.RETRY // -1: response was not valid HTTP
        else -> Outcome.FAIL // includes 3xx: redirects are not followed
    }

    /**
     * Posts [body] until it is accepted, rejected, or `ts + 110 s` passes. Every attempt sends the same [body] so the
     * desktop can dedupe by id. [post] returns the HTTP status and gets `timeoutMs`, the total time budget of that
     * attempt (connect + response), which never exceeds the time left. Backoff is 1, 2, 4, 8, 15, 15… s.
     */
    suspend fun deliver(
        body: String,
        ts: Long,
        now: () -> Long,
        sleep: suspend (Long) -> Unit,
        post: suspend (body: String, timeoutMs: Int) -> Int,
    ): Report {
        val deadline = ts + SEND_DEADLINE_MS
        var delay = FIRST_DELAY_MS
        var lastError: String? = null
        while (true) {
            val remaining = deadline - now()
            if (remaining <= 0) return Report.Failed(lastError?.let { "expired ($it)" } ?: "expired")
            val status = try {
                post(body, minOf(MAX_ATTEMPT_TIMEOUT_MS, remaining).toInt())
            } catch (e: IOException) {
                null
            }
            when (status?.let(::classify)) {
                Outcome.SENT -> return if (now() > ts + DESKTOP_MAX_AGE_MS) Report.Failed("expired") else Report.Sent
                Outcome.FAIL -> return Report.Failed("http $status")
                Outcome.RETRY -> lastError = "http $status"
                null -> lastError = "network"
            }
            val left = deadline - now()
            if (left > 0) sleep(minOf(delay, left))
            delay = minOf(delay * 2, MAX_DELAY_MS)
        }
    }
}
