package ch.cslavenc.otpforwarder.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import ch.cslavenc.otpforwarder.core.Payload
import ch.cslavenc.otpforwarder.core.SmsDecision
import ch.cslavenc.otpforwarder.core.SmsPart
import ch.cslavenc.otpforwarder.data.ForwardLog
import ch.cslavenc.otpforwarder.data.SettingsStore
import ch.cslavenc.otpforwarder.relay.ForwardWorker
import kotlin.concurrent.thread

/** SMS_RECEIVED → SmsDecision → seal and enqueue. Quick synchronous work only, no network. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        // Receipt time is the payload ts (wire contract); the SMSC timestamp only feeds the stale guard.
        val now = System.currentTimeMillis()
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.mapNotNull { message ->
            message?.let {
                SmsPart(it.displayOriginatingAddress.orEmpty(), it.displayMessageBody.orEmpty(), it.timestampMillis)
            }
        } ?: return
        val waits = mutableListOf<() -> Unit>()
        for (decision in SmsDecision.decide(parts, SettingsStore(context).senders(), now)) {
            // One failing SMS must not crash the process and lose the others; the message could hold the code.
            try {
                handle(context, decision, now)?.let(waits::add)
            } catch (e: Exception) {
                log(context, decision.sender, now, ForwardLog.Status.FAILED, "internal")
            }
        }
        if (waits.isEmpty()) return
        // A cold-started process may be killed once onReceive returns: stay alive until WorkManager has stored the
        // requests. The wait blocks, so it runs off the main thread.
        val pending = goAsync()
        thread {
            try {
                waits.forEach { it() }
            } finally {
                pending.finish()
            }
        }
    }

    /** Returns the wait for an enqueued forward, else null. */
    private fun handle(context: Context, decision: SmsDecision, now: Long): (() -> Unit)? {
        when (decision) {
            is SmsDecision.Ignore -> Unit // other senders are not logged
            is SmsDecision.NoCode -> log(context, decision.sender, now, ForwardLog.Status.NO_CODE, "")
            is SmsDecision.TooOld ->
                log(context, decision.sender, now, ForwardLog.Status.TOO_OLD, "${decision.ageMinutes} min old")
            is SmsDecision.Forward -> return ForwardWorker.forward(context, decision.sender, decision.code, now)
        }
        return null
    }

    private fun log(context: Context, sender: String, ts: Long, status: ForwardLog.Status, detail: String) =
        ForwardLog.add(context, ForwardLog.Entry(Payload.newId(), Payload.cleanSender(sender), ts, status, detail))
}
