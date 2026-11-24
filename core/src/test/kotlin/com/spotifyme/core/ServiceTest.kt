package com.spotifyme.core

import com.spotifyme.core.api.ApiException
import com.spotifyme.core.api.HttpClient
import com.spotifyme.core.api.HttpRequest
import com.spotifyme.core.api.HttpResponse
import com.spotifyme.core.api.HttpTransport
import com.spotifyme.core.api.ReccoBeatsApi
import com.spotifyme.core.api.SpotifyApi
import com.spotifyme.core.engine.FeatureCache
import com.spotifyme.core.engine.PlaylistService
import com.spotifyme.core.model.AudioFeatures
import com.spotifyme.core.model.Mood
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tests of the networking plumbing against a fake transport (no real network). */
class ServiceTest {

    /** Routes requests by URL prefix and records them. */
    private class FakeTransport(private val routes: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return routes(request)
        }
    }

    private fun ok(body: String) = HttpResponse(200, emptyMap(), body)

    private fun spotifyTrack(id: String, artist: String) =
        """{"id":"$id","name":"Song $id","duration_ms":200000,"artists":[{"id":"$artist","name":"$artist"}],"external_ids":{"isrc":"ISRC$id"}}"""

    @Test
    fun urlEncodingUsesPercentTwenty() {
        assertEquals(
            "https://x/search?limit=10&q=artist%3A%22Daft%20Punk%22",
            HttpClient.url("https://x/search", mapOf("q" to "artist:\"Daft Punk\"", "limit" to "10")),
        )
    }

    @Test
    fun retriesOn429ThenSucceeds() = runTest {
        var calls = 0
        val transport = FakeTransport {
            calls++
            if (calls == 1) HttpResponse(429, mapOf("retry-after" to "0"), "") else ok("{}")
        }
        assertEquals("{}", HttpClient(transport).get("https://x"))
        assertEquals(2, calls)
    }

    @Test(expected = ApiException.Unauthorized::class)
    fun unauthorizedIsSurfaced() = runTest {
        HttpClient(FakeTransport { HttpResponse(401, emptyMap(), "") }).get("https://x")
    }

    @Test
    fun generateAndSaveRunningPlaylist() = runTest {
        val tracks = (1..40).map { spotifyTrack("t$it", "artist$it") }
        val transport = FakeTransport { req ->
            val url = req.url
            when {
                url.contains("/me/top/tracks") -> ok("""{"items":[${tracks.joinToString(",")}]}""")
                url.contains("/me/playlists") -> ok("""{"id":"pl1","external_urls":{"spotify":"https://open.spotify.com/playlist/pl1"}}""")
                url.contains("/playlists/pl1/items") -> ok("""{"snapshot_id":"s"}""")
                url.contains("api.spotify.com") -> ok("""{"items":[]}""")
                url.contains("/audio-features") -> {
                    val ids = Regex("ids=([^&]*)").find(url)!!.groupValues[1].replace("%2C", ",").split(",")
                    val content = ids.joinToString(",") {
                        """{"href":"https://open.spotify.com/track/$it","tempo":180.0,"energy":0.8,"valence":0.5}"""
                    }
                    ok("""{"content":[$content]}""")
                }
                url.contains("/track/recommendation") -> ok("""{"content":[]}""")
                else -> HttpResponse(404, emptyMap(), "not found: $url")
            }
        }
        val http = HttpClient(transport)
        val spotify = SpotifyApi({ "token" }, http)
        val service = PlaylistService(spotify, ReccoBeatsApi(http), bpmFallbacks = emptyList(), cache = FeatureCache(null))

        val progress = mutableListOf<PlaylistService.Progress>()
        val result = service.generate(Mood.RUNNING_180) { progress += it }
        assertEquals(30, result.size)
        assertTrue(result.all { it.features?.tempo == 180.0 })
        assertEquals(PlaylistService.Progress.ReadingHistory, progress.first())

        val saved = service.save(result, Mood.RUNNING_180, existingPlaylistId = null)
        assertEquals("pl1", saved.id)
        val put = transport.requests.single { it.method == "PUT" }
        assertTrue(put.body!!.startsWith("""{"uris":["spotify:track:"""))
        assertEquals("Bearer token", put.headers["Authorization"])
    }

    @Test
    fun shortPlaylistIsFilledWithRecommendations() = runTest {
        // Only 5 of the user's tracks fit Running 180; recommendations fill the rest of the 30.
        val mine = (1..5).map { spotifyTrack("t$it", "artist$it") }
        val recs = (1..40).joinToString(",") {
            """{"trackTitle":"Rec $it","artists":[{"name":"Rec Artist $it","href":"https://open.spotify.com/artist/ra$it"}],"href":"https://open.spotify.com/track/rec$it","durationMs":200000}"""
        }
        var recommendationCalls = 0
        val transport = FakeTransport { req ->
            val url = req.url
            when {
                url.contains("/me/top/tracks") -> ok("""{"items":[${mine.joinToString(",")}]}""")
                url.contains("api.spotify.com") -> ok("""{"items":[]}""")
                url.contains("/audio-features") -> {
                    val ids = Regex("ids=([^&]*)").find(url)!!.groupValues[1].replace("%2C", ",").split(",")
                    val content = ids.joinToString(",") {
                        val tempo = if (it.startsWith("rec")) 150.0 else 180.0 // recommendations miss the running window
                        """{"href":"https://open.spotify.com/track/$it","tempo":$tempo,"energy":0.8,"valence":0.5}"""
                    }
                    ok("""{"content":[$content]}""")
                }
                url.contains("/track/recommendation") -> {
                    recommendationCalls++
                    ok("""{"content":[$recs]}""")
                }
                else -> HttpResponse(404, emptyMap(), "not found: $url")
            }
        }
        val http = HttpClient(transport)
        val service = PlaylistService(SpotifyApi({ "token" }, http), ReccoBeatsApi(http), bpmFallbacks = emptyList(), cache = FeatureCache(null))

        val result = service.generate(Mood.RUNNING_180)
        assertEquals(30, result.size)
        assertEquals(5, result.count { it.id.startsWith("t") })
        // One call while reading history, at least one more aimed at the mood (tempo=180).
        assertTrue(recommendationCalls >= 2)
        assertTrue(transport.requests.any { it.url.contains("/track/recommendation") && it.url.contains("tempo=180") })
    }

    @Test
    fun featureCachePersistsAndRemembersMisses() = runTest {
        val file = File.createTempFile("features", ".json").apply { delete() }
        try {
            var now = 1_000L
            val cache = FeatureCache(file, missTtlMillis = 100, clock = { now })
            cache.store(AudioFeatures(tempo = 120.0, energy = 0.5, valence = 0.5, source = "x"), "a")
            cache.recordMiss("b")
            // BPM-only data never replaces full mood data.
            cache.store(AudioFeatures(tempo = 99.0, source = "Deezer"), "a")
            cache.save()

            val reloaded = FeatureCache(file, missTtlMillis = 100, clock = { now })
            assertEquals(120.0, reloaded.features("a")!!.tempo!!, 0.0)
            assertEquals(listOf("c"), reloaded.idsNeedingLookup(listOf("a", "b", "c")))
            now += 200
            assertEquals(listOf("b", "c"), reloaded.idsNeedingLookup(listOf("a", "b", "c")))

            reloaded.clear()
            assertNull(FeatureCache(file).features("a"))
            assertFalse(FeatureCache(file).idsNeedingLookup(listOf("a")).isEmpty())
        } finally {
            file.delete()
        }
    }
}
