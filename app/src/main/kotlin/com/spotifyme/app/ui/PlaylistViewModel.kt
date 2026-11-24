package com.spotifyme.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotifyme.app.AppContainer
import com.spotifyme.core.engine.PlaylistService
import com.spotifyme.core.model.CandidateTrack
import com.spotifyme.core.model.MoodChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistUiState(
    /** The latest version of the mood (null once a custom mood is deleted). */
    val choice: MoodChoice?,
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

class PlaylistViewModel(private val choiceId: String, private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(
        container.choice(choiceId).let { PlaylistUiState(it, hasSavedPlaylist = it != null && container.savedPlaylistId(it) != null) }
    )
    val state: StateFlow<PlaylistUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        load(forceRefresh = false)
        // Edits to a custom mood apply right away: pick up the new rules and rebuild the list.
        viewModelScope.launch {
            container.customMoods
                .map { container.choice(choiceId) }
                .distinctUntilChanged()
                .drop(1)
                .collect { choice ->
                    _state.update { it.copy(choice = choice) }
                    if (choice != null) load(forceRefresh = false)
                }
        }
    }

    fun load(forceRefresh: Boolean) {
        val choice = _state.value.choice ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, savedUrl = null) }
            try {
                val tracks = container.service.generate(choice, forceRefresh) { progress ->
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
        val choice = _state.value.choice ?: return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            try {
                val playlist = container.service.save(_state.value.tracks, choice, container.savedPlaylistId(choice))
                container.setSavedPlaylistId(playlist.id, choice)
                _state.update {
                    it.copy(savedUrl = playlist.url ?: "https://open.spotify.com/playlist/${playlist.id}", hasSavedPlaylist = true)
                }
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
