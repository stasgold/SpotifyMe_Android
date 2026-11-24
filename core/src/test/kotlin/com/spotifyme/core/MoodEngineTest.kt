package com.spotifyme.core

import com.spotifyme.core.api.GetSongBpmApi
import com.spotifyme.core.api.ReccoBeatsApi
import com.spotifyme.core.engine.MoodEngine
import com.spotifyme.core.model.AudioFeatures
import com.spotifyme.core.model.CandidateTrack
import com.spotifyme.core.model.Mood
import com.spotifyme.core.model.Track
import com.spotifyme.core.model.TrackOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MoodEngineTest {
    /** Deterministic RNG so tests are repeatable. */
    private val random = Random(42)
    private val engine = MoodEngine(MoodEngine.Options(targetCount = 30, maxPerArtist = 2, jitter = 0.0))

    private fun track(
        id: String,
        artist: String? = null,
        tempo: Double? = null,
        energy: Double? = null,
        valence: Double? = null,
        dance: Double? = null,
        acoustic: Double? = null,
        origins: Set<TrackOrigin> = setOf(TrackOrigin.TOP_MEDIUM_TERM),
    ): CandidateTrack {
        val artistName = artist ?: "Artist $id"
        val features = if (tempo == null && energy == null && valence == null) null else
            AudioFeatures(tempo, energy, valence, dance, acoustic, source = "test")
        return CandidateTrack(
            Track(id, "Song $id", listOf(artistName), primaryArtistId = artistName, durationMs = 200_000, isrc = null),
            features, origins,
        )
    }

    private fun ids(mood: Mood, pool: List<CandidateTrack>, e: MoodEngine = engine) =
        e.playlist(mood, pool, random).map { it.id }

    @Test
    fun cadenceDeviationCountsHalfTime() {
        assertEquals(0.0, MoodEngine.cadenceDeviation(180.0, 180.0)!!, 0.0)
        assertEquals(0.0, MoodEngine.cadenceDeviation(90.0, 180.0)!!, 0.0)
        assertEquals(4.0, MoodEngine.cadenceDeviation(88.0, 180.0)!!, 0.0)
        assertEquals(2.0, MoodEngine.cadenceDeviation(172.0, 170.0)!!, 0.0)
        assertNull(MoodEngine.cadenceDeviation(null, 180.0))
    }

    @Test
    fun running180SelectsMatchingTempos() {
        val pool = listOf(
            track("a", tempo = 180.0, energy = 0.8),
            track("b", tempo = 90.0, energy = 0.9),   // half-time
            track("c", tempo = 178.0, energy = null), // BPM only (fallback source) is allowed
            track("d", tempo = 120.0, energy = 0.9),  // wrong tempo
            track("e", tempo = 181.0, energy = 0.3),  // too calm
            track("f", tempo = 170.0, energy = 0.8),  // 170 is outside even the widened window
        )
        assertEquals(setOf("a", "b", "c"), ids(Mood.RUNNING_180, pool).toSet())
    }

    @Test
    fun running170() {
        val pool = listOf(
            track("a", tempo = 170.0, energy = 0.8),
            track("b", tempo = 85.0, energy = 0.8),
            track("c", tempo = 180.0, energy = 0.8),
        )
        assertEquals(setOf("a", "b"), ids(Mood.RUNNING_170, pool).toSet())
    }

    @Test
    fun runningWidensWindowOnlyWhenNeeded() {
        val pool = listOf(track("exact", tempo = 180.0, energy = 0.8), track("near", tempo = 187.0, energy = 0.8))
        // Not enough tracks at ±5, so ±8 is used and "near" is included.
        assertEquals(setOf("exact", "near"), ids(Mood.RUNNING_180, pool).toSet())

        val small = MoodEngine(MoodEngine.Options(targetCount = 1, maxPerArtist = 2, jitter = 0.0))
        assertEquals(listOf("exact"), ids(Mood.RUNNING_180, pool, small))
    }

    @Test
    fun slowDownIsCalmAndWindsDown() {
        val pool = listOf(
            track("a", tempo = 70.0, energy = 0.2, acoustic = 0.9),
            track("b", tempo = 90.0, energy = 0.4, acoustic = 0.5),
            track("c", tempo = 95.0, energy = 0.3, acoustic = 0.7),
            track("loud", tempo = 80.0, energy = 0.8),
            track("fast", tempo = 140.0, energy = 0.2),
        )
        assertEquals(listOf("b", "c", "a"), ids(Mood.SLOW_DOWN, pool)) // energy 0.4 -> 0.3 -> 0.2
    }

    @Test
    fun speedUpBuilds() {
        val pool = listOf(
            track("mid", tempo = 125.0, energy = 0.75),
            track("high", tempo = 148.0, energy = 0.95),
            track("low", tempo = 102.0, energy = 0.62),
            track("slow", tempo = 90.0, energy = 0.9),
        )
        assertEquals(listOf("low", "mid", "high"), ids(Mood.SPEED_UP, pool))
    }

    @Test
    fun feelingGood() {
        val pool = listOf(
            track("happy", tempo = 120.0, energy = 0.7, valence = 0.9, dance = 0.8),
            track("sad", tempo = 120.0, energy = 0.7, valence = 0.2, dance = 0.8),
            track("intense", tempo = 120.0, energy = 0.95, valence = 0.9, dance = 0.8),
            track("stiff", tempo = 120.0, energy = 0.7, valence = 0.9, dance = 0.3),
        )
        assertEquals(listOf("happy"), ids(Mood.FEELING_GOOD, pool))
    }

    @Test
    fun maxTwoTracksPerArtist() {
        val pool = (0 until 5).map { track("t$it", artist = "Same", tempo = 180.0, energy = 0.8) }
        assertEquals(2, ids(Mood.RUNNING_180, pool).size)
    }

    @Test
    fun surpriseMeMixesForgottenAndNew() {
        val pool = (0 until 20).map { track("old$it", origins = setOf(TrackOrigin.TOP_LONG_TERM)) } +
            (0 until 20).map { track("new$it", origins = setOf(TrackOrigin.RECOMMENDATION)) } +
            (0 until 20).map { track("now$it", origins = setOf(TrackOrigin.TOP_SHORT_TERM, TrackOrigin.TOP_LONG_TERM)) }

        val result = ids(Mood.SURPRISE_ME, pool)
        assertEquals(30, result.size)
        assertEquals(12, result.count { it.startsWith("old") })
        assertEquals(18, result.count { it.startsWith("new") })
        assertTrue("Currently played tracks are not a surprise", result.none { it.startsWith("now") })
    }

    @Test
    fun spreadArtistsAvoidsRepeats() {
        val tracks = listOf(track("1", "A"), track("2", "A"), track("3", "B"), track("4", "B"))
        val spread = MoodEngine.spreadArtists(tracks).map { it.track.primaryArtist }
        for (i in 1 until spread.size) assertNotEquals(spread[i - 1], spread[i])
    }

    @Test
    fun deduplicateMergesOrigins() {
        val merged = MoodEngine.deduplicated(
            listOf(track("x", origins = setOf(TrackOrigin.LIKED)), track("x", origins = setOf(TrackOrigin.TOP_LONG_TERM)))
        )
        assertEquals(1, merged.size)
        assertEquals(setOf(TrackOrigin.LIKED, TrackOrigin.TOP_LONG_TERM), merged[0].origins)
    }

    @Test
    fun reccoBeatsHrefParsing() {
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", ReccoBeatsApi.spotifyId("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"))
        assertNull(ReccoBeatsApi.spotifyId("https://open.spotify.com/artist/abc"))
        assertNull(ReccoBeatsApi.spotifyId(null))
    }

    @Test
    fun getSongBpmNoResultShape() {
        assertNull(GetSongBpmApi.parseResults("""{"search":{"error":"no result"}}"""))
        val found = GetSongBpmApi.parseResults("""{"search":[{"title":"x","tempo":"172","artist":{"name":"Y"}}]}""")
        assertEquals("172", found!!.single().tempo)
    }
}
