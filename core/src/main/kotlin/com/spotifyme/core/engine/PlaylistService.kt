package com.spotifyme.core.engine

import com.spotifyme.core.api.BpmLookup
import com.spotifyme.core.api.ReccoBeatsApi
import com.spotifyme.core.api.SpotifyApi
import com.spotifyme.core.model.CandidateTrack
import com.spotifyme.core.model.Mood
import com.spotifyme.core.model.Track
import com.spotifyme.core.model.TrackOrigin
import com.spotifyme.core.model.AudioFeatures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Orchestrates: gather listening history -> attach audio features -> apply mood rules -> save to Spotify. */
class PlaylistService(
    private val spotify: SpotifyApi,
    private val recco: ReccoBeatsApi = ReccoBeatsApi(),
    private val bpmFallbacks: List<BpmLookup>,
    private val cache: FeatureCache,
    private val engine: MoodEngine = MoodEngine(),
    /** Fewer than this many tracks for a mood triggers fetching recommendations. */
    private val minimumTracks: Int = 20,
    /** Cap on slow, one-by-one fallback BPM lookups per generation. */
    private val maxFallbackLookups: Int = 60,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Progress {
        data object ReadingHistory : Progress
        data class AnalyzingTracks(val done: Int, val total: Int) : Progress
        data object FindingMoreTracks : Progress
        data object ChoosingTracks : Progress
    }

    /** Serializes generate/save so the shared pool is never built twice at once. */
    private val mutex = Mutex()
    private val pool = LinkedHashMap<String, CandidateTrack>()
    private var poolBuiltAt: Long? = null

    // region Public

    suspend fun generate(
        mood: Mood,
        forceRefresh: Boolean = false,
        progress: (Progress) -> Unit = {},
    ): List<CandidateTrack> = mutex.withLock {
        ensurePool(forceRefresh, progress)

        if (mood.cadence != null) fillMissingTempos(progress)

        progress(Progress.ChoosingTracks)
        var result = engine.playlist(mood, pool.values.toList())

        if (result.size < minimumTracks && mood != Mood.SURPRISE_ME) {
            progress(Progress.FindingMoreTracks)
            expandPool(mood, result.map { it.id }, progress)
            progress(Progress.ChoosingTracks)
            result = engine.playlist(mood, pool.values.toList())
        }
        cache.save()
        result
    }

    /** Writes the tracks to Spotify. Reuses [existingPlaylistId] if it still exists, else creates a new playlist. */
    suspend fun save(tracks: List<CandidateTrack>, mood: Mood, existingPlaylistId: String?): SpotifyApi.CreatedPlaylist {
        val playlist = if (existingPlaylistId != null && spotify.playlistExists(existingPlaylistId)) {
            SpotifyApi.CreatedPlaylist(existingPlaylistId, "https://open.spotify.com/playlist/$existingPlaylistId")
        } else {
            spotify.createPlaylist(mood.playlistName, mood.playlistDescription)
        }
        spotify.replacePlaylistItems(playlist.id, tracks.map { it.track.uri })
        return playlist
    }

    // endregion

    // region Pool

    private suspend fun ensurePool(forceRefresh: Boolean, progress: (Progress) -> Unit) {
        val stale = poolBuiltAt?.let { clock() - it > 3_600_000 } ?: true
        if (!forceRefresh && !stale && pool.isNotEmpty()) return

        progress(Progress.ReadingHistory)
        pool.clear()

        // Required: if these fail (e.g. expired login) the error is shown to the user.
        coroutineScope {
            val short = async { spotify.topTracks(SpotifyApi.TimeRange.SHORT_TERM) }
            val medium = async { spotify.topTracks(SpotifyApi.TimeRange.MEDIUM_TERM) }
            val long = async { spotify.topTracks(SpotifyApi.TimeRange.LONG_TERM) }
            add(short.await(), TrackOrigin.TOP_SHORT_TERM)
            add(medium.await(), TrackOrigin.TOP_MEDIUM_TERM)
            add(long.await(), TrackOrigin.TOP_LONG_TERM)
        }

        // Optional sources: a failure here just means a smaller pool.
        optional { spotify.recentlyPlayed() }?.let { add(it, TrackOrigin.RECENTLY_PLAYED) }
        optional { spotify.likedTracks() }?.let { add(it, TrackOrigin.LIKED) }

        optional { spotify.topArtists(SpotifyApi.TimeRange.MEDIUM_TERM, limit = 10) }?.forEach { artist ->
            optional { spotify.searchTracks(byArtist = artist.name) }?.let { tracks ->
                // Keep only tracks actually by this artist (search can be fuzzy).
                add(tracks.filter { it.primaryArtistId == null || it.primaryArtistId == artist.id }, TrackOrigin.ARTIST_SEARCH)
            }
        }

        // New music for "Surprise Me", seeded by recent favorites.
        val seeds = pool.values.filter { TrackOrigin.TOP_SHORT_TERM in it.origins }.take(5).map { it.id }
        if (seeds.isNotEmpty()) {
            optional { recco.recommendations(seeds, size = 60) }?.let { add(it, TrackOrigin.RECOMMENDATION) }
        }

        attachFeatures(progress)
        poolBuiltAt = clock()
    }

    private fun add(tracks: List<Track>, origin: TrackOrigin) {
        for (track in tracks) {
            val existing = pool[track.id]
            pool[track.id] = existing?.copy(origins = existing.origins + origin)
                ?: CandidateTrack(track, features = null, origins = setOf(origin))
        }
    }

    // endregion

    // region Features

    private suspend fun attachFeatures(progress: (Progress) -> Unit) {
        val ids = pool.keys.toList()
        val toFetch = cache.idsNeedingLookup(ids)
        toFetch.chunked(ReccoBeatsApi.BATCH_SIZE).forEachIndexed { index, chunk ->
            progress(Progress.AnalyzingTracks(done = index * ReccoBeatsApi.BATCH_SIZE, total = toFetch.size))
            val found = optional { recco.audioFeatures(chunk) } ?: return@forEachIndexed
            for (id in chunk) {
                val features = found[id]
                if (features != null) {
                    cache.store(features, id)
                } else if (!bpmFallbacksEnabled) {
                    cache.recordMiss(id)
                }
            }
        }
        progress(Progress.AnalyzingTracks(done = toFetch.size, total = toFetch.size))
        for (id in ids) {
            pool[id]?.let { pool[id] = it.copy(features = cache.features(id)) }
        }
    }

    private val bpmFallbacksEnabled: Boolean get() = bpmFallbacks.isNotEmpty()

    /** For running moods: look up BPM one by one (GetSongBPM, then Deezer) for tracks without a tempo. */
    private suspend fun fillMissingTempos(progress: (Progress) -> Unit) {
        if (!bpmFallbacksEnabled) return
        val missing = pool.values.filter { it.features?.tempo == null }.map { it.track }
        val candidates = cache.idsNeedingLookup(missing.map { it.id }).toSet()
        val lookups = missing.filter { it.id in candidates }.take(maxFallbackLookups)

        lookups.forEachIndexed { index, track ->
            progress(Progress.AnalyzingTracks(done = index, total = lookups.size))
            var found = false
            for (service in bpmFallbacks) {
                val bpm = optional { service.bpm(track) } ?: continue
                val current = pool[track.id]?.features ?: AudioFeatures(source = service.name)
                val features = current.copy(
                    tempo = bpm,
                    source = if (current.hasMoodData) current.source else service.name,
                )
                cache.store(features, track.id)
                pool[track.id]?.let { pool[track.id] = it.copy(features = features) }
                found = true
                break
            }
            if (!found) cache.recordMiss(track.id)
        }
    }

    // endregion

    // region Expansion

    private suspend fun expandPool(mood: Mood, seeds: List<String>, progress: (Progress) -> Unit) {
        val fallbackSeeds = pool.values
            .filter { TrackOrigin.TOP_SHORT_TERM in it.origins || TrackOrigin.TOP_MEDIUM_TERM in it.origins }
            .map { it.id }
        val seedIds = (seeds + fallbackSeeds).take(5)
        if (seedIds.isEmpty()) return

        data class Target(val tempo: Double?, val energy: Double?, val valence: Double?)
        val target = when (mood) {
            Mood.SLOW_DOWN -> Target(80.0, 0.3, null)
            Mood.SPEED_UP -> Target(128.0, 0.8, null)
            Mood.RUNNING_180 -> Target(180.0, 0.8, null)
            Mood.RUNNING_170 -> Target(170.0, 0.8, null)
            Mood.FEELING_GOOD -> Target(118.0, 0.7, 0.85)
            Mood.SURPRISE_ME -> Target(null, null, null)
        }
        val recs = optional {
            recco.recommendations(seedIds, size = 100, tempo = target.tempo, energy = target.energy, valence = target.valence)
        } ?: return
        add(recs, TrackOrigin.RECOMMENDATION)
        attachFeatures(progress)
    }

    // endregion
}

/** Like Swift's `try?`: returns null on failure, but never swallows coroutine cancellation. */
private inline fun <T> optional(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
