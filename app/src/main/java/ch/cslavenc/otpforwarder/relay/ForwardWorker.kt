package ch.cslavenc.otpforwarder.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.cslavenc.otpforwarder.core.Crypto
import ch.cslavenc.otpforwarder.core.Delivery
import ch.cslavenc.otpforwarder.core.Payload
import ch.cslavenc.otpforwarder.data.ForwardLog
import ch.cslavenc.otpforwarder.data.PairingStore
import ch.cslavenc.otpforwarder.data.isKeystoreFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

/** Publishes one sealed body to the relay, retrying the identical body in-process until the delivery deadline. */
class ForwardWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID)
        val ts = inputData.getLong(KEY_TS, 0L)
        val relay = inputData.getString(KEY_RELAY)
        val topic = inputData.getString(KEY_TOPIC)
        val body = inputData.getString(KEY_BODY)
        if (id == null || ts == 0L || relay == null || topic == null || body == null) return Result.failure()

        val report = Delivery.deliver(
            body = body,
            ts = ts,
            now = System::currentTimeMillis,
            sleep = { delay(it) },
            post = { b, timeoutMs -> withContext(Dispatchers.IO) { post("$relay/$topic", b, timeoutMs) } },
        )
        // Never Result.retry(): WorkManager's backoff loses expedited status and ignores the deadline.
        return when (report) {
            Delivery.Report.Sent -> {
                ForwardLog.update(applicationContext, id, ts, ForwardLog.Status.SENT)
                Result.success()
            }
            is Delivery.Report.Failed -> {
                ForwardLog.update(applicationContext, id, ts, ForwardLog.Status.FAILED, report.detail)
                Result.failure()
            }
        }
    }

    /** Only called for expedited work on API < 31, where WorkManager runs it in its own foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Forwarding", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Forwarding code to the desktop")
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val TAG = "forward"
        private const val KEY_ID = "id"
        private const val KEY_TS = "ts"
        private const val KEY_RELAY = "relay"
        private const val KEY_TOPIC = "topic"
        private const val KEY_BODY = "body"
        private const val CHANNEL_ID = "forwarding"
        private const val NOTIFICATION_ID = 1

        /** Well inside the ~10 s a receiver may run after goAsync(). */
        private const val ENQUEUE_TIMEOUT_S = 5L

        /**
         * Seals [code] for the current pairing, logs QUEUED and enqueues delivery. Shared by the SMS receiver and the
         * test button; runs synchronously (prefs, one Keystore encryption) and never touches the network.
         * Returns a blocking wait (call it off the main thread) until WorkManager has stored the request, or null if
         * nothing was enqueued; it logs FAILED "enqueue" if storing failed or timed out.
         */
        fun forward(context: Context, sender: String, code: String, ts: Long): (() -> Unit)? {
            val id = Payload.newId()
            val log = { status: ForwardLog.Status, detail: String ->
                ForwardLog.add(context, ForwardLog.Entry(id, Payload.cleanSender(sender), ts, status, detail))
            }
            val store = PairingStore(context)
            val (pairing, body) = try {
                val pairing = store.current()
                if (pairing == null) {
                    log(ForwardLog.Status.NOT_PAIRED, "")
                    return null
                }
                pairing to Crypto.seal(store.encryptCipher(pairing), pairing.topic, Payload.encode(id, code, sender, ts))
            } catch (e: Exception) {
                if (!isKeystoreFailure(e)) throw e
                log(ForwardLog.Status.FAILED, "keystore")
                return null
            }
            // Logged before enqueueing so the worker's update always finds the entry.
            log(ForwardLog.Status.QUEUED, "")
            val failed = { ForwardLog.update(context, id, ts, ForwardLog.Status.FAILED, "enqueue") }
            val operation = try {
                enqueue(context, id, ts, pairing.relay, pairing.topic, body)
            } catch (e: Exception) {
                failed()
                return null
            }
            return {
                try {
                    operation.result.get(ENQUEUE_TIMEOUT_S, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    failed()
                }
            }
        }

        /** The input holds the ciphertext only, never the code. */
        private fun enqueue(context: Context, id: String, ts: Long, relay: String, topic: String, body: String): Operation {
            val request = OneTimeWorkRequestBuilder<ForwardWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(
                    workDataOf(KEY_ID to id, KEY_TS to ts, KEY_RELAY to relay, KEY_TOPIC to topic, KEY_BODY to body),
                )
                .addTag(TAG)
                .build()
            return WorkManager.getInstance(context).enqueue(request)
        }

        /** Returns the HTTP status; throws IOException on network errors. Never logs the URL or body. */
        private fun post(url: String, body: String, timeoutMs: Int): Int {
            val bytes = body.toByteArray(Charsets.US_ASCII)
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                // timeoutMs is the attempt's whole budget; splitting it roughly bounds the attempt, and Delivery's
                // deadline check is the real guard.
                connection.connectTimeout = maxOf(1, timeoutMs / 2)
                connection.readTimeout = maxOf(1, timeoutMs / 2)
                connection.setRequestProperty("X-Firebase", "no")
                connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                return connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }
}
