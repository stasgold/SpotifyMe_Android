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
import com.spotifyme.core.model.CustomMood
import com.spotifyme.core.model.Mood
import com.spotifyme.core.model.MoodChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class SpotifyMeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

object AppConfig {
    const val REDIRECT_URI: String = BuildConfig.REDIRECT_URI
    const val DASHBOARD_URL = "https://developer.spotify.com/dashboard"

    /** Build-time defaults (local.properties / CI secrets). Values entered in the app take precedence. */
    const val DEFAULT_CLIENT_ID: String = BuildConfig.SPOTIFY_CLIENT_ID
    const val DEFAULT_GETSONGBPM_KEY: String = BuildConfig.GETSONGBPM_API_KEY

    /** Spotify Client IDs are 32 hexadecimal characters. */
    fun isValidClientId(id: String): Boolean {
        val trimmed = id.trim()
        return trimmed.length == 32 && trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
}

/** App-wide state and services shared by the screens (the Android twin of the iOS AppModel). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainer(context: Context) {
    /** Outlives screens and rotation, e.g. for finishing a sign-in. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val prefs = context.getSharedPreferences("spotifyme", Context.MODE_PRIVATE)
    private val tokenStore = TokenStore(context)
    private val cache = FeatureCache(File(context.filesDir, "SpotifyMe/features.json"))
    private val json = Json { ignoreUnknownKeys = true }

    // region Spotify settings (entered on the device, so each person can use their own Spotify app)

    val spotifyClientId: String get() = prefs.getString(KEY_CLIENT_ID, null) ?: AppConfig.DEFAULT_CLIENT_ID.trim()
    val getSongBpmApiKey: String get() = prefs.getString(KEY_BPM_KEY, null) ?: AppConfig.DEFAULT_GETSONGBPM_KEY.trim()

    private val _isConfigured = MutableStateFlow(AppConfig.isValidClientId(spotifyClientId))
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    /**
     * Saves credentials entered on the device and rebuilds the Spotify clients.
     * Changing the Client ID signs out, since tokens belong to one Spotify app.
     */
    fun configure(clientId: String, getSongBpmApiKey: String) {
        val newId = clientId.trim()
        val clientChanged = newId != spotifyClientId
        prefs.edit {
            putString(KEY_CLIENT_ID, newId)
            putString(KEY_BPM_KEY, getSongBpmApiKey.trim())
        }
        if (clientChanged) auth.signOut()
        buildClients()
        _isConfigured.value = AppConfig.isValidClientId(spotifyClientId)
    }

    // endregion

    // region Spotify clients

    private class Clients(val auth: SpotifyAuth, val service: PlaylistService)

    private fun makeClients(): Clients {
        val auth = SpotifyAuth(spotifyClientId, AppConfig.REDIRECT_URI, tokenStore)
        val fallbacks = buildList<BpmLookup> {
            if (getSongBpmApiKey.isNotBlank()) add(GetSongBpmApi(getSongBpmApiKey))
            add(DeezerApi())
        }
        return Clients(auth, PlaylistService(SpotifyApi(auth), bpmFallbacks = fallbacks, cache = cache))
    }

    private val clients = MutableStateFlow(makeClients())

    val auth: SpotifyAuth get() = clients.value.auth
    val service: PlaylistService get() = clients.value.service

    private fun buildClients() {
        clients.value = makeClients()
    }

    private val authFlow = clients.flatMapLatest { it.auth.isSignedIn }

    val isSignedIn: StateFlow<Boolean> = authFlow.stateIn(scope, SharingStarted.Eagerly, auth.isSignedIn.value)

    // endregion

    // region Errors and sign-in

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

    // endregion

    // region Custom moods

    private val customMoodsSerializer = ListSerializer(CustomMood.serializer())

    private val _customMoods = MutableStateFlow(loadCustomMoods())
    val customMoods: StateFlow<List<CustomMood>> = _customMoods.asStateFlow()

    private fun loadCustomMoods(): List<CustomMood> {
        val stored = prefs.getString(KEY_CUSTOM_MOODS, null) ?: return emptyList()
        return runCatching { json.decodeFromString(customMoodsSerializer, stored) }.getOrDefault(emptyList())
    }

    fun customMood(id: String): CustomMood? = _customMoods.value.firstOrNull { it.id == id }

    /** Adds the mood, or replaces the one with the same ID. */
    fun saveCustomMood(mood: CustomMood) {
        val list = _customMoods.value.toMutableList()
        val index = list.indexOfFirst { it.id == mood.id }
        if (index >= 0) list[index] = mood else list += mood
        persist(list)
    }

    fun deleteCustomMood(mood: CustomMood) {
        prefs.edit { remove(playlistKey(MoodChoice.Custom(mood))) }
        persist(_customMoods.value.filterNot { it.id == mood.id })
    }

    private fun persist(list: List<CustomMood>) {
        _customMoods.value = list
        prefs.edit { putString(KEY_CUSTOM_MOODS, json.encodeToString(customMoodsSerializer, list)) }
    }

    /** Resolves a [MoodChoice.id] to the current built-in or custom mood. */
    fun choice(id: String): MoodChoice? =
        if (id.startsWith(CUSTOM_PREFIX)) customMood(id.removePrefix(CUSTOM_PREFIX))?.let { MoodChoice.Custom(it) }
        else Mood.fromKey(id)?.let { MoodChoice.BuiltIn(it) }

    // endregion

    // region Saved playlist IDs (so refreshing a mood updates the same playlist)

    private fun playlistKey(choice: MoodChoice) = "playlist.${choice.id}"

    fun savedPlaylistId(choice: MoodChoice): String? = prefs.getString(playlistKey(choice), null)

    fun setSavedPlaylistId(id: String, choice: MoodChoice) = prefs.edit { putString(playlistKey(choice), id) }

    // endregion

    companion object {
        const val CUSTOM_PREFIX = "custom."
        private const val KEY_CLIENT_ID = "settings.spotifyClientID"
        private const val KEY_BPM_KEY = "settings.getSongBPMAPIKey"
        private const val KEY_CUSTOM_MOODS = "customMoods"
    }
}
