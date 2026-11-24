package com.spotifyme.core.model

import kotlinx.serialization.Serializable

/** A track in the candidate pool, independent of which API it came from. */
data class Track(
    /** Spotify track ID (base-62). */
    val id: String,
    val name: String,
    val artists: List<String>,
    val primaryArtistId: String?,
    val durationMs: Int,
    val isrc: String?,
) {
    val uri: String get() = "spotify:track:$id"
    val primaryArtist: String get() = artists.firstOrNull().orEmpty()
    val artistLine: String get() = artists.joinToString(", ")

    /** Key used to limit tracks per artist. Falls back to the name when the ID is missing. */
    internal val artistKey: String get() = primaryArtistId ?: primaryArtist.lowercase()
}

/**
 * Audio features in Spotify's former scale (0..1 for most fields, BPM for tempo).
 * Any field may be missing depending on which provider answered.
 */
@Serializable
data class AudioFeatures(
    val tempo: Double? = null,
    val energy: Double? = null,
    val valence: Double? = null,
    val danceability: Double? = null,
    val acousticness: Double? = null,
    val source: String,
) {
    /** True when we have the mood fields (energy/valence), not just BPM. */
    val hasMoodData: Boolean get() = energy != null && valence != null
}

/** Where a track in the pool came from. Used by "Surprise Me". */
enum class TrackOrigin {
    TOP_SHORT_TERM, TOP_MEDIUM_TERM, TOP_LONG_TERM, RECENTLY_PLAYED, LIKED, ARTIST_SEARCH, RECOMMENDATION
}

/** A track together with its features and origins. */
data class CandidateTrack(
    val track: Track,
    val features: AudioFeatures?,
    val origins: Set<TrackOrigin>,
) {
    val id: String get() = track.id
}
