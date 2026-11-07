package ch.cslavenc.otpforwarder.core

import org.json.JSONObject
import java.util.UUID

/** Plaintext JSON of a message (wire contract v1, docs/wire-contract.md §5). */
object Payload {
    const val VERSION = 1
    const val MAX_SENDER_CHARS = 64
    const val MAX_BYTES = 512
    private val CODE = Regex("[A-Za-z0-9]{4,10}")

    fun isValidCode(code: String): Boolean = CODE.matches(code)

    fun newId(): String = UUID.randomUUID().toString()

    /** Drops control characters (Unicode Cc) and keeps the first 64 code points, as the desktop requires. */
    fun cleanSender(raw: String): String {
        val codePoints = raw.codePoints()
            .filter { !Character.isISOControl(it) }
            .limit(MAX_SENDER_CHARS.toLong())
            .toArray()
        return String(codePoints, 0, codePoints.size)
    }

    /**
     * Built by hand so the key order is fixed. id and code are validated to plain ASCII, so only the sender needs
     * quoting; Android's JSONObject.quote escapes '/' as "\/" while the JVM jar does not, so bytes can differ for such
     * senders, but both are valid JSON with the same value.
     */
    fun encode(id: String, code: String, sender: String, ts: Long): ByteArray {
        require(Crypto.isValidToken(id)) { "invalid id" }
        require(isValidCode(code)) { "invalid code" }
        val json = """{"v":$VERSION,"id":"$id","code":"$code","sender":${JSONObject.quote(cleanSender(sender))},"ts":$ts}"""
        val bytes = json.toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES) { "payload too large" }
        return bytes
    }
}
