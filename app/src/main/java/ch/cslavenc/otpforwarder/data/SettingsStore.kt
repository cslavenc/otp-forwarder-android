package ch.cslavenc.otpforwarder.data

import android.content.Context
import androidx.core.content.edit
import ch.cslavenc.otpforwarder.core.SenderFilter

/** Allowed senders, stored as the user typed them; SenderFilter normalizes at compare time. */
class SettingsStore(context: Context) {
    private val prefs = context.prefs()

    fun senders(): Set<String> = prefs.getStringSet(KEY_SENDERS, null)?.toSet().orEmpty()

    /** False if [raw] is blank, too long, or already allowed in another spelling. */
    fun addSender(raw: String): Boolean {
        val sender = raw.trim()
        val current = senders()
        if (sender.isEmpty() || sender.length > MAX_SENDER_CHARS) return false
        if (current.any { SenderFilter.normalize(it) == SenderFilter.normalize(sender) }) return false
        prefs.edit { putStringSet(KEY_SENDERS, current + sender) }
        return true
    }

    fun removeSender(sender: String) {
        prefs.edit { putStringSet(KEY_SENDERS, senders() - sender) }
    }

    private companion object {
        const val KEY_SENDERS = "allowed_senders"
        const val MAX_SENDER_CHARS = 64
    }
}
