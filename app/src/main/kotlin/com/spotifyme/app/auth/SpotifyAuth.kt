package com.spotifyme.app.auth

import android.net.Uri
import android.util.Base64
import com.spotifyme.core.api.AccessTokenProvider
import com.spotifyme.core.api.ApiException
import com.spotifyme.core.api.HttpClient
import com.spotifyme.core.api.HttpRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** PKCE (Proof Key for Code Exchange) values for Spotify's authorization code flow. */
class Pkce {
    val verifier: String
    val challenge: String

    init {
        val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        verifier = bytes.base64Url()
        challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)).base64Url()
    }

    private fun ByteArray.base64Url(): String =
        Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

sealed class AuthException(message: String) : Exception(message) {
    class NotSignedIn : AuthException("Please sign in to Spotify.")
    class MissingCode : AuthException("Spotify did not return an authorization code.")
    class StateMismatch : AuthException("Sign-in response did not match the request. Please try again.")
    class Denied(reason: String) : AuthException("Spotify sign-in was cancelled ($reason).")
}

/**
 * Spotify sign-in with Authorization Code + PKCE (no client secret on the device),
 * encrypted token storage, and automatic refresh.
 */
class SpotifyAuth(
    private val clientId: String,
    private val redirectUri: String,
    private val store: TokenStore,
    private val http: HttpClient = HttpClient(),
) : AccessTokenProvider {

    private val mutex = Mutex()
    private var tokens: Tokens? = store.load()
    private val _isSignedIn = MutableStateFlow(tokens != null)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    /** URL to open in a Custom Tab. */
    fun authorizationUri(): Uri {
        val pkce = Pkce()
        val state = UUID.randomUUID().toString()
        store.savePending(PendingSignIn(pkce.verifier, state))
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", pkce.challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .build()
    }

    /** Handle the redirect (spotifyme://callback?code=...&state=...). */
    suspend fun handleCallback(uri: Uri) {
        uri.getQueryParameter("error")?.let {
            store.clearPending()
            throw AuthException.Denied(it)
        }
        val pending = store.loadPending()
        if (pending == null || uri.getQueryParameter("state") != pending.state) throw AuthException.StateMismatch()
        val code = uri.getQueryParameter("code") ?: throw AuthException.MissingCode()
        store.clearPending()

        val newTokens = requestTokens(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri,
                "client_id" to clientId,
                "code_verifier" to pending.verifier,
            ),
            previousRefreshToken = null,
        )
        mutex.withLock { persist(newTokens) }
    }

    fun signOut() {
        tokens = null
        store.clear()
        _isSignedIn.value = false
    }

    // region AccessTokenProvider

    /** The mutex makes concurrent callers share one refresh. */
    override suspend fun accessToken(): String = mutex.withLock {
        val current = tokens ?: throw AuthException.NotSignedIn()
        if (current.expiresAt - System.currentTimeMillis() > 60_000) return@withLock current.accessToken

        try {
            val refreshed = requestTokens(
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to current.refreshToken,
                    "client_id" to clientId,
                ),
                previousRefreshToken = current.refreshToken,
            )
            persist(refreshed)
            refreshed.accessToken
        } catch (e: ApiException.Http) {
            // A rejected refresh token means the user must sign in again.
            if (e.status == 400) signOut()
            throw e
        }
    }

    // endregion

    private fun persist(newTokens: Tokens) {
        tokens = newTokens
        store.save(newTokens)
        _isSignedIn.value = true
    }

    private suspend fun requestTokens(form: Map<String, String>, previousRefreshToken: String?): Tokens {
        val body = http.send(
            HttpRequest(
                url = "https://accounts.spotify.com/api/token",
                method = "POST",
                body = HttpClient.formBody(form),
                contentType = "application/x-www-form-urlencoded",
            )
        )
        val response = HttpClient.json.decodeFromString<TokenResponse>(body)
        val refresh = response.refreshToken ?: previousRefreshToken ?: throw AuthException.NotSignedIn()
        return Tokens(response.accessToken, refresh, System.currentTimeMillis() + response.expiresIn * 1000L)
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Int,
    )

    companion object {
        val SCOPES = listOf(
            "user-top-read",
            "user-read-recently-played",
            "user-library-read",
            "playlist-modify-private",
            "playlist-modify-public",
        )
    }
}
