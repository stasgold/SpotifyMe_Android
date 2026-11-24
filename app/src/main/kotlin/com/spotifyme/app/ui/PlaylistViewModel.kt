package com.spotifyme.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifyme.app.AppContainer
import com.spotifyme.core.engine.PlaylistService
import com.spotifyme.core.model.CandidateTrack
import com.spotifyme.core.model.Mood
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistUiState(
    val tracks: List<CandidateTrack> = emptyList(),
    val status: String = PlaylistService.Progress.ReadingHistory.text,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val savedUrl: String? = null,
    val hasSavedPlaylist: Boolean = false,
) {
    val durationText: String
        get() {
            val minutes = tracks.sumOf { it.track.durationMs.toLong() } / 60_000
            return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
        }
}

class PlaylistViewModel(val mood: Mood, private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(PlaylistUiState(hasSavedPlaylist = container.savedPlaylistId(mood) != null))
    val state: StateFlow<PlaylistUiState> = _state.asStateFlow()

    init {
        load(forceRefresh = false)
    }

    fun load(forceRefresh: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, savedUrl = null) }
            try {
                val tracks = container.service.generate(mood, forceRefresh) { progress ->
                    _state.update { it.copy(status = progress.text) }
                }
                _state.update { it.copy(tracks = tracks) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.handle(e)
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun save() {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            try {
                val playlist = container.service.save(_state.value.tracks, mood, container.savedPlaylistId(mood))
                container.setSavedPlaylistId(playlist.id, mood)
                _state.update { it.copy(savedUrl = playlist.url ?: "https://open.spotify.com/playlist/${playlist.id}", hasSavedPlaylist = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.handle(e)
            } finally {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }
}

val PlaylistService.Progress.text: String
    get() = when (this) {
        PlaylistService.Progress.ReadingHistory -> "Reading your listening history…"
        is PlaylistService.Progress.AnalyzingTracks -> "Analyzing tempo and mood ($done/$total)…"
        PlaylistService.Progress.FindingMoreTracks -> "Finding more matching tracks…"
        PlaylistService.Progress.ChoosingTracks -> "Choosing tracks…"
    }
