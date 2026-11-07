package ch.cslavenc.otpforwarder.data

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import androidx.core.content.edit
import ch.cslavenc.otpforwarder.core.Crypto
import ch.cslavenc.otpforwarder.core.Pairing
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** The paired desktop: relay and topic in prefs, the AES key non-exportable in the Android Keystore. */
class PairingStore(context: Context) {
    class Current(val relay: String, val topic: String, val alias: String) {
        override fun toString() = "Current(relay=$relay)"
    }

    private val prefs = context.prefs()

    /** Null when not paired, or when the prefs point at a key that is gone. */
    fun current(): Current? {
        val alias = prefs.getString(KEY_ALIAS, null) ?: return null
        val relay = prefs.getString(KEY_RELAY, null) ?: return null
        val topic = prefs.getString(KEY_TOPIC, null) ?: return null
        return if (keyStore().containsAlias(alias)) Current(relay, topic, alias) else null
    }

    /**
     * Replaces any previous pairing. The key goes in under a fresh alias before the prefs commit, so a crash leaves
     * at worst an orphan key, never a topic paired with the wrong key.
     */
    fun save(pairing: Pairing) {
        val ks = keyStore()
        val alias = ALIAS_PREFIX + UUID.randomUUID()
        try {
            ks.setEntry(alias, KeyStore.SecretKeyEntry(SecretKeySpec(pairing.key, "AES")), PROTECTION)
        } finally {
            pairing.key.fill(0)
        }
        val committed = prefs.edit()
            .putString(KEY_ALIAS, alias)
            .putString(KEY_RELAY, pairing.relay)
            .putString(KEY_TOPIC, pairing.topic)
            .commit()
        if (!committed) {
            ks.deleteEntry(alias)
            throw IOException("could not save pairing")
        }
        deleteKeys(ks, keep = alias)
    }

    fun clear() {
        // apply() is enough: without its key a leftover pairing reads as unpaired.
        prefs.edit { remove(KEY_ALIAS).remove(KEY_RELAY).remove(KEY_TOPIC) }
        deleteKeys(keyStore(), keep = null)
    }

    /** A cipher initialised without parameters, so the Keystore picks a fresh random nonce. */
    fun encryptCipher(current: Current): Cipher {
        val key = keyStore().getKey(current.alias, null) ?: throw UnrecoverableKeyException("pairing key missing")
        return Cipher.getInstance(Crypto.TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    private fun deleteKeys(ks: KeyStore, keep: String?) {
        ks.aliases().toList().filter { it.startsWith(ALIAS_PREFIX) && it != keep }.forEach(ks::deleteEntry)
    }

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_PREFIX = "pairing-"
        const val KEY_ALIAS = "pairing_alias"
        const val KEY_RELAY = "pairing_relay"
        const val KEY_TOPIC = "pairing_topic"

        // No user-auth or unlocked-device requirement: SMS arrive while the phone is locked.
        val PROTECTION: KeyProtection = KeyProtection.Builder(KeyProperties.PURPOSE_ENCRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
    }
}

/** How Keystore, prefs and the seal self-check fail; callers map these to a fixed message without details. */
internal fun isKeystoreFailure(e: Exception) =
    e is GeneralSecurityException || e is ProviderException || e is IOException || e is IllegalStateException
