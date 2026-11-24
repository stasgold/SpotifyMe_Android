package com.spotifyme.core

import com.spotifyme.core.api.GetSongBpmApi
import com.spotifyme.core.api.ReccoBeatsApi
import com.spotifyme.core.engine.MoodEngine
import com.spotifyme.core.model.AudioFeatures
import com.spotifyme.core.model.CandidateTrack
import com.spotifyme.core.model.CustomMood
import com.spotifyme.core.model.Mood
import com.spotifyme.core.model.MoodChoice
import com.spotifyme.core.model.Track
import com.spotifyme.core.model.TrackOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
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

    // region Custom moods

    private fun range(min: Double, max: Double, on: Boolean = false) = CustomMood.Range(on, min, max)

    @Test
    fun customMoodAppliesEveryEnabledRange() {
        val mood = CustomMood(
            tempo = range(100.0, 120.0, on = true),
            energy = range(0.5, 0.9, on = true),
            valence = range(0.6, 1.0, on = true),
            danceability = range(0.0, 1.0),
            acousticness = range(0.0, 0.3, on = true),
        )
        val pool = listOf(
            track("ok", tempo = 110.0, energy = 0.7, valence = 0.8, acoustic = 0.1),
            track("slow", tempo = 90.0, energy = 0.7, valence = 0.8, acoustic = 0.1),
            track("sad", tempo = 110.0, energy = 0.7, valence = 0.3, acoustic = 0.1),
            track("acoustic", tempo = 110.0, energy = 0.7, valence = 0.8, acoustic = 0.9),
            track("noTempo", energy = 0.7, valence = 0.8, acoustic = 0.1),
        )
        assertEquals(listOf("ok"), engine.playlist(mood, pool, random).map { it.id })
    }

    @Test
    fun customMoodHalfTimeAndTempoOrder() {
        var mood = CustomMood(
            tempo = range(170.0, 190.0, on = true), countHalfTime = true,
            energy = range(0.0, 1.0), valence = range(0.0, 1.0),
            danceability = range(0.0, 1.0), acousticness = range(0.0, 1.0),
            order = CustomMood.Order.TEMPO_RISING,
        )
        val pool = listOf(track("a", tempo = 186.0), track("b", tempo = 88.0), track("c", tempo = 180.0), track("d", tempo = 120.0))
        assertEquals(listOf("b", "c", "a"), engine.playlist(mood, pool, random).map { it.id })
        mood = mood.copy(countHalfTime = false)
        assertEquals(setOf("a", "c"), engine.playlist(mood, pool, random).map { it.id }.toSet())
    }

    @Test
    fun customMoodSourcesCountAndArtistCap() {
        var mood = CustomMood(
            tempo = range(0.0, 300.0), energy = range(0.0, 1.0), valence = range(0.0, 1.0),
            danceability = range(0.0, 1.0), acousticness = range(0.0, 1.0),
            sources = setOf(CustomMood.Source.LIKED), targetCount = 3, maxPerArtist = 1,
        )
        val pool = listOf(
            track("l1", artist = "X", energy = 0.5, origins = setOf(TrackOrigin.LIKED)),
            track("l2", artist = "X", energy = 0.5, origins = setOf(TrackOrigin.LIKED)),
            track("l3", artist = "Y", energy = 0.5, origins = setOf(TrackOrigin.LIKED)),
            track("t1", artist = "Z", energy = 0.5, origins = setOf(TrackOrigin.TOP_SHORT_TERM)),
        )
        val ids = engine.playlist(mood, pool, random).map { it.id }
        assertEquals(2, ids.size)
        assertTrue("t1" !in ids)
        mood = mood.copy(sources = setOf(CustomMood.Source.LIKED, CustomMood.Source.TOP_TRACKS))
        assertEquals(3, engine.playlist(mood, pool, random).size)
    }

    @Test
    fun customMoodRoundTripsThroughJson() {
        val mood = CustomMood(name = "Evening", order = CustomMood.Order.ENERGY_FALLING, targetCount = 25)
        val json = Json.encodeToString(CustomMood.serializer(), mood)
        assertEquals(mood, Json.decodeFromString(CustomMood.serializer(), json))
    }

    @Test
    fun choiceIdsMatchIos() {
        assertEquals("running180", MoodChoice.BuiltIn(Mood.RUNNING_180).id)
        assertEquals("custom.abc", MoodChoice.Custom(CustomMood(id = "abc")).id)
        assertEquals("Any track from your library", CustomMood(
            tempo = range(0.0, 1.0), energy = range(0.0, 1.0), valence = range(0.0, 1.0),
            danceability = range(0.0, 1.0), acousticness = range(0.0, 1.0),
        ).summary)
    }

    // endregion

    // region Filling short playlists with recommendations

    private val rec = setOf(TrackOrigin.RECOMMENDATION)

    @Test
    fun fillTopsUpWithClosestRecommendations() {
        val small = MoodEngine(MoodEngine.Options(targetCount = 4, maxPerArtist = 2, jitter = 0.0))
        val pool = listOf(
            track("a", tempo = 180.0, energy = 0.8),
            track("b", tempo = 90.0, energy = 0.9),
            track("near", tempo = 178.0, energy = 0.5, origins = rec),  // fails the energy rule, but close
            track("half", tempo = 90.0, energy = 0.4, origins = rec),   // half-time, calm
            track("far", tempo = 120.0, energy = 0.8, origins = rec),
            track("unknown", origins = rec),                            // no audio data
            track("mine", tempo = 120.0, energy = 0.9),                 // own track that doesn't match: never a filler
        )
        assertEquals(setOf("a", "b"), small.playlist(Mood.RUNNING_180, pool, random).map { it.id }.toSet())
        assertEquals(
            setOf("a", "b", "near", "half"),
            small.playlist(Mood.RUNNING_180, pool, random, fill = true).map { it.id }.toSet(),
        )
    }

    @Test
    fun fillReachesTargetAndKeepsArtistCap() {
        val pool = listOf(track("calm", tempo = 70.0, energy = 0.2)) +
            (1..40).map { track("r$it", artist = "Artist ${it % 10}", tempo = 120.0, energy = 0.7, origins = rec) }
        val result = engine.playlist(Mood.SLOW_DOWN, pool, random, fill = true)
        assertEquals(21, result.size) // 1 match + 10 artists × 2 recommended tracks
        assertTrue(result.groupingBy { it.track.primaryArtist }.eachCount().values.all { it <= 2 })
        // Still ordered by the mood's rule: highest energy first, calmest last.
        assertEquals("calm", result.last().id)
    }

    @Test
    fun customMoodFillsOnlyWhenNewMusicIsAllowed() {
        val base = CustomMood(
            tempo = range(100.0, 110.0, on = true), energy = range(0.0, 1.0), valence = range(0.0, 1.0),
            danceability = range(0.0, 1.0), acousticness = range(0.0, 1.0), targetCount = 3,
        )
        val pool = listOf(
            track("match", tempo = 105.0, energy = 0.5),
            track("r1", tempo = 130.0, energy = 0.5, origins = rec),
            track("r2", tempo = 140.0, energy = 0.5, origins = rec),
        )
        assertEquals(3, engine.playlist(base, pool, random, fill = true).size)
        val noNewMusic = base.copy(sources = setOf(CustomMood.Source.TOP_TRACKS, CustomMood.Source.LIKED))
        assertEquals(listOf("match"), engine.playlist(noNewMusic, pool, random, fill = true).map { it.id })
    }

    // endregion

    @Test
    fun getSongBpmNoResultShape() {
        assertNull(GetSongBpmApi.parseResults("""{"search":{"error":"no result"}}"""))
        val found = GetSongBpmApi.parseResults("""{"search":[{"title":"x","tempo":"172","artist":{"name":"Y"}}]}""")
        assertEquals("172", found!!.single().tempo)
    }
}
