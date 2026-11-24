package com.spotifyme.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.spotifyme.app.auth.AuthException
import com.spotifyme.app.auth.SpotifyAuth
import com.spotifyme.app.auth.TokenStore
import com.spotifyme.core.api.ApiException
import com.spotifyme.core.api.BpmLookup
import com.spotifyme.core.api.DeezerApi
import com.spotifyme.core.api.GetSongBpmApi
import com.spotifyme.core.api.SpotifyApi
import com.spotifyme.core.engine.FeatureCache
import com.spotifyme.core.engine.PlaylistService
import com.spotifyme.core.model.Mood
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class SpotifyMeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Values injected at build time from local.properties / environment (see README.md). */
object AppConfig {
    val spotifyClientId: String = BuildConfig.SPOTIFY_CLIENT_ID
    val getSongBpmApiKey: String = BuildConfig.GETSONGBPM_API_KEY
    const val REDIRECT_URI: String = BuildConfig.REDIRECT_URI

    val isConfigured: Boolean get() = spotifyClientId.isNotBlank()
}

/** App-wide state and services shared by the screens (the Android twin of the iOS AppModel). */
class AppContainer(context: Context) {
    /** Outlives screens and rotation, e.g. for finishing a sign-in. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val auth = SpotifyAuth(AppConfig.spotifyClientId, AppConfig.REDIRECT_URI, TokenStore(context))
    private val cache = FeatureCache(File(context.filesDir, "SpotifyMe/features.json"))
    private val prefs = context.getSharedPreferences("spotifyme", Context.MODE_PRIVATE)

    val service: PlaylistService = run {
        val fallbacks = buildList<BpmLookup> {
            if (AppConfig.getSongBpmApiKey.isNotBlank()) add(GetSongBpmApi(AppConfig.getSongBpmApiKey))
            add(DeezerApi())
        }
        PlaylistService(SpotifyApi(auth), bpmFallbacks = fallbacks, cache = cache)
    }

    val isSignedIn: StateFlow<Boolean> get() = auth.isSignedIn

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isCompletingSignIn = MutableStateFlow(false)
    val isCompletingSignIn: StateFlow<Boolean> = _isCompletingSignIn.asStateFlow()

    fun showError(message: String?) {
        _errorMessage.value = message
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    /** Exchanges the authorization code from the redirect for tokens. */
    fun completeSignIn(callback: Uri) {
        scope.launch {
            _isCompletingSignIn.value = true
            try {
                auth.handleCallback(callback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message)
            } finally {
                _isCompletingSignIn.value = false
            }
        }
    }

    fun signOut() = auth.signOut()

    suspend fun clearCache() = cache.clear()

    /** Call when a request fails; signs out if the session is no longer valid. */
    fun handle(error: Throwable) {
        if (error is CancellationException) return
        showError(error.message ?: error.toString())
        if (error is ApiException.Unauthorized || error is AuthException.NotSignedIn) signOut()
    }

    // region Saved playlist IDs (so refreshing a mood updates the same playlist)

    fun savedPlaylistId(mood: Mood): String? = prefs.getString("playlist.${mood.key}", null)

    fun setSavedPlaylistId(id: String, mood: Mood) = prefs.edit { putString("playlist.${mood.key}", id) }

    // endregion
}
