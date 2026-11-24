package com.spotifyme.core.engine

import com.spotifyme.core.model.CandidateTrack
import com.spotifyme.core.model.CustomMood
import com.spotifyme.core.model.Mood
import com.spotifyme.core.model.MoodChoice
import com.spotifyme.core.model.TrackOrigin
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Pure, deterministic (given a [Random]) selection and ordering of tracks for a mood.
 * No networking here, so the rules can be unit-tested.
 */
class MoodEngine(val options: Options = Options()) {

    data class Options(
        val targetCount: Int = 30,
        val maxPerArtist: Int = 2,
        /** Random jitter added to scores so each refresh differs a little. */
        val jitter: Double = 0.05,
    )

    fun playlist(mood: Mood, pool: List<CandidateTrack>, random: Random = Random.Default): List<CandidateTrack> {
        val deduped = deduplicated(pool)
        return when (mood) {
            Mood.SLOW_DOWN -> slowDown(deduped, random)
            Mood.SPEED_UP -> speedUp(deduped, random)
            Mood.RUNNING_180, Mood.RUNNING_170 -> running(mood.cadence!!, deduped, random)
            Mood.FEELING_GOOD -> feelingGood(deduped, random)
            Mood.SURPRISE_ME -> surpriseMe(deduped, random)
        }
    }

    // region Slow Down

    private fun slowDown(pool: List<CandidateTrack>, random: Random): List<CandidateTrack> {
        val scored = pool.mapNotNull { c ->
            val f = c.features ?: return@mapNotNull null
            val energy = f.energy ?: return@mapNotNull null
            if (energy >= 0.45) return@mapNotNull null
            if (f.tempo != null && f.tempo !in 60.0..100.0) return@mapNotNull null
            val acoustic = f.acousticness ?: 0.3
            // Lower energy and more acoustic score higher.
            c to ((0.45 - energy) / 0.45 * 0.6 + acoustic * 0.4)
        }
        // Wind down: highest energy first, calmest last.
        return pick(scored, random).sortedByDescending { it.features?.energy ?: 0.0 }
    }

    // endregion

    // region Speed Up

    private fun speedUp(pool: List<CandidateTrack>, random: Random): List<CandidateTrack> {
        val scored = pool.mapNotNull { c ->
            val energy = c.features?.energy ?: return@mapNotNull null
            val tempo = c.features.tempo ?: return@mapNotNull null
            if (energy < 0.6 || tempo !in 100.0..150.0) return@mapNotNull null
            c to energy
        }
        // Build: tempo and energy climb together.
        return pick(scored, random).sortedBy { intensity(it) }
    }

    private fun intensity(c: CandidateTrack): Double {
        val tempo = c.features?.tempo ?: 100.0
        val energy = c.features?.energy ?: 0.6
        return (tempo - 100) / 50 * 0.5 + energy * 0.5
    }

    // endregion

    // region Running

    private fun running(cadence: Double, pool: List<CandidateTrack>, random: Random): List<CandidateTrack> {
        // Start strict and widen the tempo window only if there are not enough tracks.
        var result = emptyList<CandidateTrack>()
        for (tolerance in listOf(5.0, 8.0)) {
            val scored = pool.mapNotNull { c ->
                val dev = cadenceDeviation(c.features?.tempo, cadence) ?: return@mapNotNull null
                if (dev > tolerance) return@mapNotNull null
                val energy = c.features?.energy
                if (energy != null && energy < 0.65) return@mapNotNull null
                // Tracks with only BPM (no energy) are allowed but ranked lower.
                val energyScore = energy ?: 0.5
                c to ((1 - dev / tolerance) * 0.6 + energyScore * 0.4)
            }
            result = pick(scored, random)
            if (result.size >= options.targetCount) break
        }
        return warmUpCoolDown(result)
    }

    // endregion

    // region Feeling Good

    private fun feelingGood(pool: List<CandidateTrack>, random: Random): List<CandidateTrack> {
        val scored = pool.mapNotNull { c ->
            val f = c.features ?: return@mapNotNull null
            val valence = f.valence ?: return@mapNotNull null
            val energy = f.energy ?: return@mapNotNull null
            val dance = f.danceability ?: 0.6
            if (valence < 0.65 || energy !in 0.5..0.85 || dance < 0.6) return@mapNotNull null
            c to (valence * 0.6 + dance * 0.4)
        }
        return spreadArtists(pick(scored, random))
    }

    // endregion

    // region Surprise Me

    private fun surpriseMe(pool: List<CandidateTrack>, random: Random): List<CandidateTrack> {
        val personal = setOf(
            TrackOrigin.TOP_SHORT_TERM, TrackOrigin.TOP_MEDIUM_TERM, TrackOrigin.TOP_LONG_TERM,
            TrackOrigin.RECENTLY_PLAYED, TrackOrigin.LIKED,
        )
        val recent = setOf(TrackOrigin.TOP_SHORT_TERM, TrackOrigin.RECENTLY_PLAYED)

        val forgotten = pool.filter { TrackOrigin.TOP_LONG_TERM in it.origins && it.origins.none { o -> o in recent } }
        val fresh = pool.filter { it.origins.none { o -> o in personal } }
        val likedNotRecent = pool.filter { TrackOrigin.LIKED in it.origins && it.origins.none { o -> o in recent } }

        val forgottenTarget = (options.targetCount * 0.4).roundToInt()
        val counts = mutableMapOf<String, Int>()
        val chosen = mutableListOf<CandidateTrack>()
        val used = mutableSetOf<String>()

        fun take(source: List<CandidateTrack>, upTo: Int) {
            for (c in source.shuffled(random)) {
                if (chosen.size >= upTo) break
                val key = c.track.artistKey
                if (c.id in used || (counts[key] ?: 0) >= options.maxPerArtist) continue
                chosen += c
                used += c.id
                counts[key] = (counts[key] ?: 0) + 1
            }
        }

        take(forgotten, forgottenTarget)
        take(fresh, options.targetCount)
        // Top up if one bucket is thin.
        take(forgotten, options.targetCount)
        take(likedNotRecent, options.targetCount)

        return spreadArtists(chosen.shuffled(random))
    }

    // endregion

    // region Custom moods

    fun playlist(custom: CustomMood, pool: List<CandidateTrack>, random: Random = Random.Default): List<CandidateTrack> {
        val engine = MoodEngine(
            options.copy(targetCount = max(1, custom.targetCount), maxPerArtist = max(1, custom.maxPerArtist))
        )
        return engine.customPlaylist(custom, deduplicated(pool), random)
    }

    fun playlist(choice: MoodChoice, pool: List<CandidateTrack>, random: Random = Random.Default): List<CandidateTrack> =
        when (choice) {
            is MoodChoice.BuiltIn -> playlist(choice.mood, pool, random)
            is MoodChoice.Custom -> playlist(choice.mood, pool, random)
        }

    private fun customPlaylist(custom: CustomMood, pool: List<CandidateTrack>, random: Random): List<CandidateTrack> {
        val allowed = custom.allowedOrigins
        val matchedTempo = mutableMapOf<String, Double>()
        val scored = pool.mapNotNull { c ->
            if (c.origins.none { it in allowed }) return@mapNotNull null
            val f = c.features
            val scores = mutableListOf<Double>()
            if (custom.tempo.isEnabled) {
                val tempo = f?.tempo?.takeIf { it > 0 } ?: return@mapNotNull null
                val options = if (custom.countHalfTime) listOf(tempo, tempo * 2) else listOf(tempo)
                val best = options.filter { custom.tempo.contains(it) }
                    .maxByOrNull { closeness(it, custom.tempo) } ?: return@mapNotNull null
                matchedTempo[c.id] = best
                scores += closeness(best, custom.tempo)
            }
            val ranges = listOf(
                custom.energy to f?.energy, custom.valence to f?.valence,
                custom.danceability to f?.danceability, custom.acousticness to f?.acousticness,
            )
            for ((range, value) in ranges) {
                if (!range.isEnabled) continue
                if (value == null || !range.contains(value)) return@mapNotNull null
                scores += closeness(value, range)
            }
            c to (if (scores.isEmpty()) 0.5 else scores.average())
        }

        var picked = pick(scored, random)
        fun tempo(c: CandidateTrack) = matchedTempo[c.id] ?: c.features?.tempo ?: 0.0
        fun energy(c: CandidateTrack) = c.features?.energy ?: 0.5
        picked = when (custom.order) {
            CustomMood.Order.BEST_MATCH_FIRST -> picked
            CustomMood.Order.SHUFFLED -> picked.shuffled(random)
            CustomMood.Order.ENERGY_RISING -> picked.sortedBy(::energy)
            CustomMood.Order.ENERGY_FALLING -> picked.sortedByDescending(::energy)
            CustomMood.Order.TEMPO_RISING -> picked.sortedBy(::tempo)
            CustomMood.Order.TEMPO_FALLING -> picked.sortedByDescending(::tempo)
            CustomMood.Order.WARM_UP_COOL_DOWN -> warmUpCoolDown(picked)
        }
        // Reordering by artist would break a deliberate energy/tempo progression.
        if (custom.spreadArtists &&
            (custom.order == CustomMood.Order.BEST_MATCH_FIRST || custom.order == CustomMood.Order.SHUFFLED)
        ) {
            picked = spreadArtists(picked)
        }
        return picked
    }

    // endregion

    // region Helpers

    /** Sort by (score + jitter), then take tracks while respecting the per-artist cap. */
    private fun pick(scored: List<Pair<CandidateTrack, Double>>, random: Random): List<CandidateTrack> {
        val jitter = options.jitter
        val ranked = scored
            .map { (c, score) -> c to score + if (jitter > 0) random.nextDouble(-jitter, jitter) else 0.0 }
            .sortedByDescending { it.second }
            .map { it.first }
        val counts = mutableMapOf<String, Int>()
        val result = mutableListOf<CandidateTrack>()
        for (c in ranked) {
            if (result.size >= options.targetCount) break
            val key = c.track.artistKey
            if ((counts[key] ?: 0) >= options.maxPerArtist) continue
            counts[key] = (counts[key] ?: 0) + 1
            result += c
        }
        return result
    }

    companion object {
        /** 1 at the middle of the range, falling to 0 at its edges. */
        internal fun closeness(value: Double, range: CustomMood.Range): Double {
            if (range.halfWidth <= 0) return 1.0
            return max(0.0, 1 - abs(value - range.midpoint) / range.halfWidth)
        }

        /**
         * How far a track's tempo is from the cadence, counting half-time
         * (one step per half beat, e.g. 90 BPM for 180 spm). Returns null when there is no tempo.
         */
        fun cadenceDeviation(tempo: Double?, cadence: Double): Double? {
            if (tempo == null || tempo <= 0) return null
            return min(abs(tempo - cadence), abs(tempo * 2 - cadence))
        }

        /** Exact-tempo tracks in the middle, with the gentlest tracks first and last. */
        internal fun warmUpCoolDown(tracks: List<CandidateTrack>): List<CandidateTrack> {
            if (tracks.size < 4) return tracks
            val byEnergy = tracks.sortedBy { it.features?.energy ?: 0.5 }
            val first = byEnergy[0]
            val last = byEnergy[1]
            val middle = tracks.filter { it.id != first.id && it.id != last.id }
            return listOf(first) + middle + listOf(last)
        }

        /** Reorder so the same artist does not play twice in a row where possible. */
        internal fun spreadArtists(tracks: List<CandidateTrack>): List<CandidateTrack> {
            val remaining = tracks.toMutableList()
            val result = mutableListOf<CandidateTrack>()
            while (remaining.isNotEmpty()) {
                val lastKey = result.lastOrNull()?.track?.artistKey
                val index = remaining.indexOfFirst { it.track.artistKey != lastKey }.takeIf { it >= 0 } ?: 0
                result += remaining.removeAt(index)
            }
            return result
        }

        /** Merge duplicates (same Spotify ID) by combining their origins. */
        internal fun deduplicated(pool: List<CandidateTrack>): List<CandidateTrack> {
            val byId = LinkedHashMap<String, CandidateTrack>()
            for (c in pool) {
                val existing = byId[c.id]
                byId[c.id] = if (existing == null) c else existing.copy(
                    origins = existing.origins + c.origins,
                    features = existing.features ?: c.features,
                )
            }
            return byId.values.toList()
        }
    }

    // endregion
}
