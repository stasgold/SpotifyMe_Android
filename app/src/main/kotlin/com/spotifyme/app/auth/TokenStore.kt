package com.spotifyme.app.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch millis. */
    val expiresAt: Long,
)

/** PKCE values for a sign-in in progress. Persisted so a sign-in survives the app being killed while in the browser. */
data class PendingSignIn(val verifier: String, val state: String)

/**
 * Stores the Spotify tokens encrypted with an AES key held in the Android Keystore
 * (the Android counterpart of the iOS app's Keychain storage).
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("spotify_auth", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Tokens? {
        val encrypted = prefs.getString(KEY_TOKENS, null) ?: return null
        return runCatching { json.decodeFromString<Tokens>(decrypt(encrypted)) }.getOrElse {
            // Key lost (e.g. device restore) or data corrupt: the user signs in again.
            clear()
            null
        }
    }

    fun save(tokens: Tokens) {
        val encrypted = runCatching { encrypt(json.encodeToString(Tokens.serializer(), tokens)) }.getOrNull() ?: return
        prefs.edit { putString(KEY_TOKENS, encrypted) }
    }

    fun clear() = prefs.edit { remove(KEY_TOKENS) }

    fun savePending(pending: PendingSignIn) = prefs.edit {
        putString(KEY_VERIFIER, pending.verifier)
        putString(KEY_STATE, pending.state)
    }

    fun loadPending(): PendingSignIn? {
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: return null
        val state = prefs.getString(KEY_STATE, null) ?: return null
        return PendingSignIn(verifier, state)
    }

    fun clearPending() = prefs.edit {
        remove(KEY_VERIFIER)
        remove(KEY_STATE)
    }

    // region Keystore

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val sealed = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = sealed.copyOfRange(0, IV_LENGTH)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH).toString(Charsets.UTF_8)
    }

    // endregion

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "spotifyme.tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val KEY_TOKENS = "tokens"
        const val KEY_VERIFIER = "pending.verifier"
        const val KEY_STATE = "pending.state"
    }
}
