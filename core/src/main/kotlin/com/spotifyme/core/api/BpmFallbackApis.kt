package com.spotifyme.core.api

import com.spotifyme.core.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Something that can look up a track's BPM when ReccoBeats has no data for it. */
interface BpmLookup {
    val name: String
    suspend fun bpm(track: Track): Double?
}

/**
 * GetSongBPM (https://getsongbpm.com/api): free with an API key.
 * Terms: a visible link back to GetSongBPM.com is REQUIRED (the app's About screen has one).
 */
class GetSongBpmApi(
    private val apiKey: String,
    private val http: HttpClient = HttpClient(),
) : BpmLookup {
    override val name = "GetSongBPM"

    override suspend fun bpm(track: Track): Double? {
        val url = HttpClient.url(
            "https://api.getsong.co/search/",
            mapOf(
                "type" to "both",
                "lookup" to "song:${track.name} artist:${track.primaryArtist}",
                "limit" to "5",
            )
        )
        val results = parseResults(http.get(url, mapOf("X-API-KEY" to apiKey))) ?: return null
        // Prefer a result whose artist matches; otherwise take the first.
        val match = results.firstOrNull { it.artist?.name.equals(track.primaryArtist, ignoreCase = true) }
            ?: results.firstOrNull()
        return match?.tempo?.toDoubleOrNull()?.takeIf { it > 0 }
    }

    @Serializable
    internal data class Result(val title: String? = null, val tempo: String? = null, val artist: ArtistDto? = null) {
        @Serializable
        data class ArtistDto(val name: String = "")
    }

    companion object {
        /** When nothing is found the API returns {"search": {"error": "no result"}} instead of an array. */
        internal fun parseResults(body: String): List<Result>? {
            val root = HttpClient.json.parseToJsonElement(body) as? JsonObject ?: return null
            val search = root["search"] as? JsonArray ?: return null
            return search.mapNotNull { runCatching { HttpClient.json.decodeFromJsonElement<Result>(it) }.getOrNull() }
        }
    }
}

/**
 * Deezer public API: free, no key for catalog lookups. Tracks found by ISRC carry a `bpm` field,
 * which is often 0 (not analyzed); 0 is treated as unknown.
 */
class DeezerApi(private val http: HttpClient = HttpClient()) : BpmLookup {
    override val name = "Deezer"

    override suspend fun bpm(track: Track): Double? {
        val isrc = track.isrc?.takeIf { it.isNotEmpty() } ?: return null
        val response: Response = http.getJson("https://api.deezer.com/track/isrc:${HttpClient.encode(isrc)}")
        return response.bpm?.takeIf { it > 0 }
    }

    @Serializable
    internal data class Response(val bpm: Double? = null)
}
