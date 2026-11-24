package com.spotifyme.core.api

import com.spotifyme.core.model.AudioFeatures
import com.spotifyme.core.model.Track
import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Locale

/**
 * ReccoBeats (https://reccobeats.com): free, no API key. Provides Spotify-style audio features
 * and recommendations, keyed by Spotify track IDs. Rate-limited (HTTP 429 + Retry-After),
 * which [HttpClient] handles.
 */
class ReccoBeatsApi(private val http: HttpClient = HttpClient()) {
    private val base = "https://api.reccobeats.com/v1"
    private val accept = mapOf("Accept" to "application/json")

    /** Audio features keyed by Spotify track ID. Tracks ReccoBeats does not know are left out. */
    suspend fun audioFeatures(spotifyIds: List<String>): Map<String, AudioFeatures> {
        val result = mutableMapOf<String, AudioFeatures>()
        for (batch in spotifyIds.chunked(BATCH_SIZE)) {
            val url = HttpClient.url("$base/audio-features", mapOf("ids" to batch.joinToString(",")))
            val response: Content<FeaturesDto> = http.getJson(url, accept)
            for (item in response.content) {
                val spotifyId = spotifyId(item.href) ?: continue
                result[spotifyId] = AudioFeatures(
                    tempo = item.tempo, energy = item.energy, valence = item.valence,
                    danceability = item.danceability, acousticness = item.acousticness,
                    source = "ReccoBeats",
                )
            }
        }
        return result
    }

    /** Recommended tracks similar to up to 5 seed tracks, optionally steered toward a target tempo/energy/valence. */
    suspend fun recommendations(
        seedIds: List<String>,
        size: Int = 50,
        tempo: Double? = null,
        energy: Double? = null,
        valence: Double? = null,
    ): List<Track> {
        val query = mutableMapOf(
            "seeds" to seedIds.take(5).joinToString(","),
            "size" to "${size.coerceIn(1, 100)}",
        )
        tempo?.let { query["tempo"] = String.format(Locale.US, "%.0f", it) }
        energy?.let { query["energy"] = String.format(Locale.US, "%.2f", it) }
        valence?.let { query["valence"] = String.format(Locale.US, "%.2f", it) }
        val response: Content<TrackDto> = http.getJson(HttpClient.url("$base/track/recommendation", query), accept)
        return response.content.mapNotNull { dto ->
            val id = spotifyId(dto.href) ?: return@mapNotNull null
            Track(
                id = id,
                name = dto.trackTitle,
                artists = dto.artists.orEmpty().map { it.name },
                primaryArtistId = dto.artists?.firstOrNull()?.let { spotifyArtistId(it.href) },
                durationMs = dto.durationMs ?: 0,
                isrc = dto.isrc,
            )
        }
    }

    companion object {
        /** Max Spotify IDs per audio-features request. */
        const val BATCH_SIZE = 40

        /** "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC" -> "4uLU6hMCjMI75M1A2tKUQC" */
        fun spotifyId(href: String?): String? = idAfter(href, "track")

        fun spotifyArtistId(href: String?): String? = idAfter(href, "artist")

        private fun idAfter(href: String?, kind: String): String? {
            if (href == null) return null
            val segments = runCatching { URI(href).path }.getOrNull()
                ?.split("/")?.filter { it.isNotEmpty() } ?: return null
            if (kind !in segments) return null
            return segments.lastOrNull()?.takeIf { it.isNotEmpty() }
        }
    }

    @Serializable
    internal data class Content<T>(val content: List<T> = emptyList())

    @Serializable
    internal data class FeaturesDto(
        val href: String? = null,
        val tempo: Double? = null,
        val energy: Double? = null,
        val valence: Double? = null,
        val danceability: Double? = null,
        val acousticness: Double? = null,
    )

    @Serializable
    internal data class TrackDto(
        val trackTitle: String = "",
        val artists: List<ArtistDto>? = null,
        val durationMs: Int? = null,
        val isrc: String? = null,
        val href: String? = null,
    ) {
        @Serializable
        data class ArtistDto(val name: String = "", val href: String? = null)
    }
}
