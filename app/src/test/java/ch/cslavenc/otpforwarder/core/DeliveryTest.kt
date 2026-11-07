package ch.cslavenc.otpforwarder.core

import ch.cslavenc.otpforwarder.core.Delivery.Outcome
import ch.cslavenc.otpforwarder.core.Delivery.Report
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class DeliveryTest {
    /** Fake clock: each post costs [POST_MS], each sleep advances the clock. */
    private class Relay(private val responses: List<Int?>) {
        var t = TS
        val bodies = mutableListOf<String>()
        val timeouts = mutableListOf<Int>()
        val remainingAtPost = mutableListOf<Long>()
        val sleeps = mutableListOf<Long>()

        fun run(start: Long = TS): Report = runBlocking {
            t = start
            Delivery.deliver(
                BODY,
                TS,
                now = { t },
                sleep = { sleeps += it; t += it },
                post = { body, timeout ->
                    bodies += body
                    timeouts += timeout
                    remainingAtPost += TS + Delivery.SEND_DEADLINE_MS - t
                    t += POST_MS
                    responses.getOrElse(bodies.size - 1) { responses.last() } ?: throw IOException("offline")
                },
            )
        }
    }

    @Test
    fun `classify maps HTTP status`() {
        for (s in listOf(200, 204, 299)) assertEquals(Outcome.SENT, Delivery.classify(s))
        for (s in listOf(-1, 408, 429, 500, 503, 599)) assertEquals(Outcome.RETRY, Delivery.classify(s))
        for (s in listOf(100, 301, 302, 400, 401, 403, 404, 413, 600)) assertEquals(Outcome.FAIL, Delivery.classify(s))
    }

    @Test
    fun `cancellation and unexpected exceptions propagate without retry`() {
        for (error in listOf(CancellationException("stopped"), IllegalStateException("bug"))) {
            var posts = 0
            var t = TS
            val thrown = runCatching {
                runBlocking {
                    Delivery.deliver(BODY, TS, now = { t }, sleep = { t += it }, post = { _, _ -> posts++; throw error })
                }
            }.exceptionOrNull()
            // Not assertSame: coroutine stack-trace recovery may rethrow a copy.
            assertEquals(error.javaClass, thrown?.javaClass)
            assertEquals(error.message, thrown?.message)
            assertEquals(1, posts)
        }
    }

    @Test
    fun `retries the identical body on retryable errors until sent`() {
        val relay = Relay(listOf(503, 429, null, 200))
        assertEquals(Report.Sent, relay.run())
        assertEquals(List(4) { BODY }, relay.bodies)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), relay.sleeps)
    }

    @Test
    fun `non-retryable status stops immediately`() {
        val relay = Relay(listOf(404))
        assertEquals(Report.Failed("http 404"), relay.run())
        assertEquals(1, relay.bodies.size)
        assertTrue(relay.sleeps.isEmpty())
    }

    @Test
    fun `gives up at ts + 110 s with capped backoff and timeouts`() {
        val relay = Relay(listOf(503))
        assertEquals(Report.Failed("expired (http 503)"), relay.run())
        assertEquals(TS + Delivery.SEND_DEADLINE_MS, relay.t)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L), relay.sleeps.take(6))
        assertTrue(relay.sleeps.all { it <= Delivery.MAX_DELAY_MS })
        relay.timeouts.indices.forEach { i ->
            assertTrue(relay.timeouts[i] <= Delivery.MAX_ATTEMPT_TIMEOUT_MS)
            assertTrue(relay.timeouts[i] <= relay.remainingAtPost[i])
        }
    }

    @Test
    fun `per-attempt timeout shrinks to the time left`() {
        val relay = Relay(listOf(null))
        assertEquals(Report.Failed("expired (network)"), relay.run(start = TS + 105_000))
        assertEquals(5_000, relay.timeouts.first())
    }

    @Test
    fun `expired work does not post`() {
        val relay = Relay(listOf(200))
        assertEquals(Report.Failed("expired"), relay.run(start = TS + Delivery.SEND_DEADLINE_MS))
        assertTrue(relay.bodies.isEmpty())
    }

    private companion object {
        const val TS = 1_700_000_000_000L
        const val POST_MS = 500L
        const val BODY = "AAECAwQFBgcICQoL"
    }
}
