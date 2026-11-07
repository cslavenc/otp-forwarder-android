package ch.cslavenc.otpforwarder.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Recent forwards, newest first. Holds sender, time and status only: never a code or SMS text. */
object ForwardLog {
    const val MAX_ENTRIES = 50
    private const val KEY_LOG = "forward_log"

    // The receiver (main thread) and the worker both read-modify-write; apply() updates memory synchronously.
    private val lock = Any()

    enum class Status { QUEUED, SENT, FAILED, NO_CODE, TOO_OLD, NOT_PAIRED }

    /** [detail] is a short fixed reason such as "http 403" or "keystore"; empty if none. */
    class Entry(val id: String, val sender: String, val ts: Long, val status: Status, val detail: String = "")

    fun entries(context: Context): List<Entry> = read(context.prefs())

    fun add(context: Context, entry: Entry) = rewrite(context) { listOf(entry) + it }

    /** Sets the outcome of [id]; re-inserts it without a sender if it was already rotated out. */
    fun update(context: Context, id: String, ts: Long, status: Status, detail: String = "") = rewrite(context) { log ->
        if (log.none { it.id == id }) {
            listOf(Entry(id, "", ts, status, detail)) + log
        } else {
            log.map { if (it.id == id) Entry(id, it.sender, it.ts, status, detail) else it }
        }
    }

    private fun rewrite(context: Context, change: (List<Entry>) -> List<Entry>) = synchronized(lock) {
        val prefs = context.prefs()
        val json = JSONArray()
        change(read(prefs)).take(MAX_ENTRIES).forEach {
            json.put(
                JSONObject()
                    .put("id", it.id)
                    .put("sender", it.sender)
                    .put("ts", it.ts)
                    .put("status", it.status.name)
                    .put("detail", it.detail),
            )
        }
        // commit(): the receiver finishes right after logging QUEUED, and a pending apply() could die with the process.
        prefs.edit(commit = true) { putString(KEY_LOG, json.toString()) }
    }

    /** A corrupt log reads as empty and is overwritten by the next write. */
    private fun read(prefs: SharedPreferences): List<Entry> {
        val raw = prefs.getString(KEY_LOG, null) ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { i -> json.optJSONObject(i)?.let(::toEntry) }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun toEntry(o: JSONObject): Entry? {
        val status = Status.entries.firstOrNull { it.name == o.optString("status") } ?: return null
        return Entry(o.optString("id"), o.optString("sender"), o.optLong("ts"), status, o.optString("detail"))
    }
}
