package com.spotifyme.core.api

import com.spotifyme.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Supplies a valid (refreshed if needed) Spotify access token. */
fun interface AccessTokenProvider {
    suspend fun accessToken(): String
}

data class Artist(val id: String, val name: String)

/**
 * The Spotify Web API calls SpotifyMe needs.
 *
 * Notes on Spotify's restrictions for Development Mode apps:
 * - Nov 2024: audio-features, audio-analysis, recommendations and related-artists were removed
 *   for new apps. Tempo/mood data comes from ReccoBeats/GetSongBPM/Deezer instead.
 * - Feb 2026: artist top-tracks and batch "get several" endpoints were removed, playlist creation
 *   moved to POST /me/playlists, and playlist item endpoints moved from /tracks to /items.
 */
class SpotifyApi(
    private val tokens: AccessTokenProvider,
    private val http: HttpClient = HttpClient(),
) {
    enum class TimeRange(val value: String) { SHORT_TERM("short_term"), MEDIUM_TERM("medium_term"), LONG_TERM("long_term") }

    data class CreatedPlaylist(val id: String, val url: String?)

    private val base = "https://api.spotify.com/v1"

    // region Reading history

    suspend fun topTracks(range: TimeRange, limit: Int = 50): List<Track> =
        get<Page<SpotifyTrack>>("/me/top/tracks", mapOf("time_range" to range.value, "limit" to "$limit"))
            .items.mapNotNull { it.model }

    suspend fun topArtists(range: TimeRange, limit: Int = 20): List<Artist> =
        get<Page<SpotifyArtist>>("/me/top/artists", mapOf("time_range" to range.value, "limit" to "$limit"))
            .items.map { Artist(it.id, it.name) }

    suspend fun recentlyPlayed(limit: Int = 50): List<Track> =
        get<Page<TrackWrapper>>("/me/player/recently-played", mapOf("limit" to "$limit"))
            .items.mapNotNull { it.track?.model }

    /** Liked Songs, newest first, up to [maxCount]. */
    suspend fun likedTracks(maxCount: Int = 200): List<Track> {
        val result = mutableListOf<Track>()
        var offset = 0
        while (result.size < maxCount) {
            val page = get<Page<TrackWrapper>>("/me/tracks", mapOf("limit" to "50", "offset" to "$offset"))
            result += page.items.mapNotNull { it.track?.model }
            if (page.next == null || page.items.isEmpty()) break
            offset += 50
        }
        return result.take(maxCount)
    }

    /** Tracks by an artist via search (replacement for the removed artist top-tracks endpoint). */
    suspend fun searchTracks(byArtist: String, limit: Int = 10): List<Track> {
        val query = "artist:\"$byArtist\""
        return get<SearchResult>("/search", mapOf("q" to query, "type" to "track", "limit" to "$limit"))
            .tracks.items.mapNotNull { it.model }
    }

    // endregion

    // region Writing playlists

    suspend fun createPlaylist(name: String, description: String, isPublic: Boolean = false): CreatedPlaylist {
        val body = HttpClient.json.encodeToString(CreatePlaylistBody(name, description, isPublic))
        val response = HttpClient.json.decodeFromString<CreatePlaylistResponse>(send("POST", "/me/playlists", body))
        return CreatedPlaylist(response.id, response.externalUrls?.get("spotify"))
    }

    /** Replaces the playlist's contents with [uris] (first 100 via PUT, the rest appended via POST). */
    suspend fun replacePlaylistItems(playlistId: String, uris: List<String>) {
        val chunks = if (uris.isEmpty()) listOf(emptyList()) else uris.chunked(100)
        chunks.forEachIndexed { index, chunk ->
            val body = HttpClient.json.encodeToString(UrisBody(chunk))
            send(if (index == 0) "PUT" else "POST", "/playlists/$playlistId/items", body)
        }
    }

    /** Whether a playlist we created earlier still exists (the user may have deleted it). */
    suspend fun playlistExists(playlistId: String): Boolean = try {
        get<MinimalPlaylist>("/playlists/$playlistId", mapOf("fields" to "id"))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    // endregion

    // region Plumbing

    private suspend inline fun <reified T> get(path: String, query: Map<String, String> = emptyMap()): T {
        val token = tokens.accessToken()
        return http.getJson(HttpClient.url(base + path, query), mapOf("Authorization" to "Bearer $token"))
    }

    private suspend fun send(method: String, path: String, body: String): String {
        val token = tokens.accessToken()
        return http.send(
            HttpRequest(
                url = base + path,
                method = method,
                headers = mapOf("Authorization" to "Bearer $token"),
                body = body,
                contentType = "application/json",
            )
        )
    }

    // endregion
}

// region Wire types

@Serializable
internal data class Page<T>(val items: List<T> = emptyList(), val next: String? = null)

@Serializable
internal data class SpotifyArtist(val id: String, val name: String)

@Serializable
internal data class SpotifyTrack(
    val id: String? = null,
    val name: String = "",
    @SerialName("duration_ms") val durationMs: Int? = null,
    val artists: List<ArtistRef> = emptyList(),
    @SerialName("external_ids") val externalIds: Map<String, String>? = null,
    @SerialName("is_local") val isLocal: Boolean? = null,
) {
    @Serializable
    data class ArtistRef(val id: String? = null, val name: String = "")

    /** null for local files or tracks without a Spotify ID. */
    val model: Track?
        get() {
            if (id == null || isLocal == true) return null
            return Track(
                id = id,
                name = name,
                artists = artists.map { it.name },
                primaryArtistId = artists.firstOrNull()?.id,
                durationMs = durationMs ?: 0,
                isrc = externalIds?.get("isrc"),
            )
        }
}

/** Play history items and saved-track items both wrap a track. */
@Serializable
internal data class TrackWrapper(val track: SpotifyTrack? = null)

@Serializable
internal data class SearchResult(val tracks: Page<SpotifyTrack> = Page())

@Serializable
internal data class MinimalPlaylist(val id: String)

@Serializable
internal data class CreatePlaylistBody(val name: String, val description: String, val public: Boolean)

@Serializable
internal data class CreatePlaylistResponse(
    val id: String,
    @SerialName("external_urls") val externalUrls: Map<String, String>? = null,
)

@Serializable
internal data class UrisBody(val uris: List<String>)

// endregion
