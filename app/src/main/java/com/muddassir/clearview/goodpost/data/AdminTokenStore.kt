package com.muddassir.clearview.goodpost.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted-at-rest storage for the administrator session (§25).
 *
 * This is the ONLY credential Good Post ever stores, and it belongs to an
 * administrator rather than to a reader: a viewer of this tab has no account and
 * therefore nothing to keep. That is the point of the file existing at all —
 * if it held a normal user's session, Good Post would have users.
 *
 * Implemented directly on the Android Keystore rather than with
 * `androidx.security:security-crypto`. That library — and
 * `EncryptedSharedPreferences` specifically — was deprecated at
 * 1.1.0-alpha07, and Google's own guidance is to use the Keystore directly, so
 * pulling in a deprecated artifact to encrypt one short string would trade a
 * real maintenance liability for a thin convenience wrapper.
 *
 * The AES-256-GCM key is generated inside the Keystore and never leaves it: this
 * code only ever holds the ciphertext and the IV.
 */
internal class AdminTokenStore(context: Context) {

    private companion object {
        const val TAG = "GoodPostAdminToken"

        const val PREFS_NAME = "clearview_goodpost_admin"
        const val KEY_PAYLOAD = "admin_session"

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "clearview_goodpost_admin_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** 128-bit authentication tag: the GCM maximum. */
        const val TAG_LENGTH_BITS = 128

        const val SEPARATOR = ":"
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Persist the session. A failure here is logged and swallowed rather than
     * thrown: the in-memory session still works for this run, and crashing on a
     * device with an unhealthy Keystore would be worse than making an
     * administrator sign in again after a restart.
     */
    fun save(session: AdminSession) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
            }
            val encrypted = cipher.doFinal(encode(session).toByteArray(Charsets.UTF_8))

            // The IV is stored beside the ciphertext — it is not a secret, and
            // GCM refuses to reuse one with the same key, so the cipher
            // regenerates it on every save.
            prefs.edit()
                .putString(KEY_PAYLOAD, base64(cipher.iv) + SEPARATOR + base64(encrypted))
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist the administrator session: ${e.message}")
        }
    }

    /**
     * Read the stored session, or null when there is none.
     *
     * Every failure path ends in [clear]. A Keystore key can legitimately become
     * undecryptable — the user resets their lock screen, the OS rekeys on a
     * security patch — and the correct response to unreadable credentials is to
     * forget them and ask for the password again, never to throw on every open
     * of the tab.
     */
    fun load(): AdminSession? {
        val payload = prefs.getString(KEY_PAYLOAD, null)
        if (payload.isNullOrBlank()) return null

        val parts = payload.split(SEPARATOR)
        if (parts.size != 2) {
            clear()
            return null
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(TAG_LENGTH_BITS, unbase64(parts[0]))
                )
            }
            val plain = String(cipher.doFinal(unbase64(parts[1])), Charsets.UTF_8)
            decode(plain).also { if (it == null) clear() }
        } catch (e: Exception) {
            Log.w(TAG, "Stored admin session is unreadable, discarding it: ${e.message}")
            clear()
            null
        }
    }

    /**
     * Remove the stored session. Called on sign-out as well as on damage, so a
     * device handed to someone else does not carry publishing rights with it.
     */
    fun clear() {
        prefs.edit().remove(KEY_PAYLOAD).apply()
    }

    // ── Codec ────────────────────────────────────────────────────────────

    private fun encode(session: AdminSession): String = JSONObject().apply {
        put("token", session.token)
        put("role", session.role)
        put("email", session.email)
        put("channelId", session.channelId ?: JSONObject.NULL)
    }.toString()

    private fun decode(raw: String?): AdminSession? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            val token = json.optString("token")
            if (token.isBlank()) return null
            AdminSession(
                token = token,
                role = json.optString("role"),
                email = json.optString("email"),
                channelId = if (json.isNull("channelId")) null else json.optString("channelId")
                    .takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            null
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // GCM must never be used with a repeated IV, so the Keystore is
                // told to randomise it per operation rather than accepting one.
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unbase64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
