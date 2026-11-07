package ch.cslavenc.otpforwarder.core

import java.util.Base64
import javax.crypto.Cipher

/** AES-256-GCM sealing of relay message bodies (wire contract v1, docs/wire-contract.md §4). */
object Crypto {
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val NONCE_LEN = 12
    const val TAG_LEN = 16
    const val MAX_BODY_CHARS = 1024
    private const val AAD_PREFIX = "otpfwd/v1/"
    private val TOKEN = Regex("[A-Za-z0-9_-]{16,64}")

    /** Topic and message id rule. */
    fun isValidToken(s: String): Boolean = TOKEN.matches(s)

    fun aad(topic: String): ByteArray = (AAD_PREFIX + topic).toByteArray(Charsets.UTF_8)

    /**
     * [cipher] is freshly initialised for ENCRYPT_MODE and its IV is the nonce. There is no nonce
     * parameter on purpose: the provider (Android Keystore in production) picks a fresh one.
     * The checks double as an on-device self-check of the Keystore IV and tag sizes.
     */
    fun seal(cipher: Cipher, topic: String, plaintext: ByteArray): String {
        val iv = checkNotNull(cipher.iv) { "cipher has no IV" }
        check(iv.size == NONCE_LEN) { "unexpected IV length" }
        cipher.updateAAD(aad(topic))
        val sealed = cipher.doFinal(plaintext)
        check(sealed.size == plaintext.size + TAG_LEN) { "unexpected tag length" }
        val body = b64(iv + sealed)
        check(body.length <= MAX_BODY_CHARS) { "body too large" }
        return body
    }

    /** Unpadded base64url. */
    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** base64url with optional padding; null on any character outside the alphabet. */
    fun b64Decode(s: String): ByteArray? =
        try {
            Base64.getUrlDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            null
        }
}
