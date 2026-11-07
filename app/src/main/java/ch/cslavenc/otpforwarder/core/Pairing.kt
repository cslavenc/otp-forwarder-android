package ch.cslavenc.otpforwarder.core

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

private const val PAIRING_VERSION = 1
private const val KEY_LEN = 32
private const val DEFAULT_HTTPS_PORT = 443
private val ROOT_PATHS = setOf("", "/")

/** A validated pairing. [relay] is a normalized https origin without a trailing slash. */
class Pairing(val relay: String, val topic: String, val key: ByteArray) {
    override fun toString() = "Pairing(relay=$relay, topic=$topic)"
}

/**
 * Parses and validates the pairing QR JSON with the same rules as the desktop's pairing.rs.
 * Failure messages are fixed strings and never echo the input, which may contain the key.
 */
fun parsePairing(json: String): Result<Pairing> {
    val o = parseObject(json) ?: return invalid("malformed JSON")
    // Strict types like serde: "1", 1.0 or 300 for v and non-string fields are malformed, not coerced.
    val v = o.opt("v")
    val relay = o.opt("relay") as? String
    val topic = o.opt("topic") as? String
    val key = o.opt("key") as? String
    if (v !is Int || v !in 0..255 || relay == null || topic == null || key == null) return invalid("malformed JSON")
    if (v != PAIRING_VERSION) return invalid("unsupported version")
    if (!Crypto.isValidToken(topic)) return invalid("topic must be 16-64 base64url characters")
    val keyBytes = Crypto.b64Decode(key)?.takeIf { it.size == KEY_LEN }
        ?: return invalid("key must be 32 bytes of base64url")
    return normalizeRelay(relay).map { Pairing(it, topic, keyBytes) }
}

/** A single JSON object with nothing but whitespace after it. */
private fun parseObject(json: String): JSONObject? =
    try {
        val tokener = JSONTokener(json)
        (tokener.nextValue() as? JSONObject)?.takeIf { tokener.nextClean() == 0.toChar() }
    } catch (e: JSONException) {
        null
    }

private fun normalizeRelay(raw: String): Result<String> {
    val uri = try {
        URI(raw)
    } catch (e: URISyntaxException) {
        return invalid("relay must be an absolute URL")
    }
    if (!uri.isAbsolute) return invalid("relay must be an absolute URL")
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host == null) return invalid("relay must be an https URL")
    if (uri.rawUserInfo != null) return invalid("relay must not contain credentials")
    // Checking the raw string also catches an empty "?" or "#", which URI may report as absent.
    if (uri.rawPath !in ROOT_PATHS || '?' in raw || '#' in raw) {
        return invalid("relay must be an origin without path, query or fragment")
    }
    val port = if (uri.port == -1 || uri.port == DEFAULT_HTTPS_PORT) "" else ":${uri.port}"
    return Result.success("https://${uri.host.lowercase(Locale.ROOT)}$port")
}

private fun <T> invalid(reason: String): Result<T> = Result.failure(IllegalArgumentException(reason))
