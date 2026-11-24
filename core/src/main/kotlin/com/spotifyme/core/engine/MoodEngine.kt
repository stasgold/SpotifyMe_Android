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

    /**
     * Tracks for a built-in mood. With [fill], a playlist that comes up short of the target count is
     * topped up with recommended tracks closest to the mood (see [topUp]).
     */
    fun playlist(mood: Mood, pool: List<CandidateTrack>, random: Random = Random.Default, fill: Boolean = false): List<CandidateTrack> {
        val deduped = deduplicated(pool)
        val target = if (fill) fillTarget(MoodChoice.BuiltIn(mood)) else null
        return when (mood) {
            Mood.SLOW_DOWN -> slowDown(deduped, random, target)
            Mood.SPEED_UP -> speedUp(deduped, random, target)
            Mood.RUNNING_180, Mood.RUNNING_170 -> running(mood.cadence!!, deduped, random, target)
            Mood.FEELING_GOOD -> feelingGood(deduped, random, target)
            Mood.SURPRISE_ME -> surpriseMe(deduped, random)
        }
    }

    // region Slow Down

    private fun slowDown(pool: List<CandidateTrack>, random: Random, fill: FillTarget?): List<CandidateTrack> {
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
        return topUp(pick(scored, random), pool, fill).sortedByDescending { it.features?.energy ?: 0.0 }
    }

    // endregion

    // region Speed Up

    private fun speedUp(pool: List<CandidateTrack>, random: Random, fill: FillTarget?): List<CandidateTrack> {
        val scored = pool.mapNotNull { c ->
            val energy = c.features?.energy ?: return@mapNotNull null
            val tempo = c.features.tempo ?: return@mapNotNull null
            if (energy < 0.6 || tempo !in 100.0..150.0) return@mapNotNull null
            c to energy
        }
        // Build: tempo and energy climb together.
        return topUp(pick(scored, random), pool, fill).sortedBy { intensity(it) }
    }

    private fun intensity(c: CandidateTrack): Double {
        val tempo = c.features?.tempo ?: 100.0
        val energy = c.features?.energy ?: 0.6
        return (tempo - 100) / 50 * 0.5 + energy * 0.5
    }

    // endregion

    // region Running

    private fun running(cadence: Double, pool: List<CandidateTrack>, random: Random, fill: FillTarget?): List<CandidateTrack> {
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
        return warmUpCoolDown(topUp(result, pool, fill))
    }

    // endregion

    // region Feeling Good

    private fun feelingGood(pool: List<CandidateTrack>, random: Random, fill: FillTarget?): List<CandidateTrack> {
        val scored = pool.mapNotNull { c ->
            val f = c.features ?: return@mapNotNull null
            val valence = f.valence ?: return@mapNotNull null
            val energy = f.energy ?: return@mapNotNull null
            val dance = f.danceability ?: 0.6
            if (valence < 0.65 || energy !in 0.5..0.85 || dance < 0.6) return@mapNotNull null
            c to (valence * 0.6 + dance * 0.4)
        }
        return spreadArtists(topUp(pick(scored, random), pool, fill))
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

    /** Like the built-in version; [fill] applies only when the mood allows new recommendations as a source. */
    fun playlist(custom: CustomMood, pool: List<CandidateTrack>, random: Random = Random.Default, fill: Boolean = false): List<CandidateTrack> {
        val engine = MoodEngine(
            options.copy(targetCount = max(1, custom.targetCount), maxPerArtist = max(1, custom.maxPerArtist))
        )
        val canFill = fill && CustomMood.Source.NEW_MUSIC in custom.sources
        val target = if (canFill) fillTarget(MoodChoice.Custom(custom)) else null
        return engine.customPlaylist(custom, deduplicated(pool), random, target)
    }

    fun playlist(choice: MoodChoice, pool: List<CandidateTrack>, random: Random = Random.Default, fill: Boolean = false): List<CandidateTrack> =
        when (choice) {
            is MoodChoice.BuiltIn -> playlist(choice.mood, pool, random, fill)
            is MoodChoice.Custom -> playlist(choice.mood, pool, random, fill)
        }

    private fun customPlaylist(custom: CustomMood, pool: List<CandidateTrack>, random: Random, fill: FillTarget?): List<CandidateTrack> {
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

        var picked = topUp(pick(scored, random), pool, fill)
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

    // region Filling short playlists

    /** What a mood is aiming for, used to steer recommendations and to rank fill-up tracks. */
    data class FillTarget(
        val tempo: Double? = null,
        val energy: Double? = null,
        val valence: Double? = null,
        /** Count half the tempo as a match too (running cadence). */
        val halfTime: Boolean = false,
    )

    /**
     * When [picked] is short of the target count, adds recommended tracks (not already picked) that
     * are closest to [fill], keeping the per-artist cap. Mood rules are relaxed for these tracks so the
     * playlist reaches its full length; tracks without audio data rank last.
     */
    private fun topUp(picked: List<CandidateTrack>, pool: List<CandidateTrack>, fill: FillTarget?): List<CandidateTrack> {
        if (fill == null || picked.size >= options.targetCount) return picked
        val result = picked.toMutableList()
        val used = picked.mapTo(mutableSetOf()) { it.id }
        val counts = picked.groupingBy { it.track.artistKey }.eachCount().toMutableMap()
        val candidates = pool
            .filter { TrackOrigin.RECOMMENDATION in it.origins && it.id !in used }
            .sortedBy { fillDistance(it, fill) }
        for (c in candidates) {
            if (result.size >= options.targetCount) break
            val key = c.track.artistKey
            if ((counts[key] ?: 0) >= options.maxPerArtist) continue
            counts[key] = (counts[key] ?: 0) + 1
            result += c
        }
        return result
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
        /** The tempo/energy/valence a mood aims for (null fields mean "anything"). */
        fun fillTarget(choice: MoodChoice): FillTarget = when (choice) {
            is MoodChoice.BuiltIn -> when (choice.mood) {
                Mood.SLOW_DOWN -> FillTarget(80.0, 0.3)
                Mood.SPEED_UP -> FillTarget(128.0, 0.8)
                Mood.RUNNING_180 -> FillTarget(180.0, 0.8, halfTime = true)
                Mood.RUNNING_170 -> FillTarget(170.0, 0.8, halfTime = true)
                Mood.FEELING_GOOD -> FillTarget(118.0, 0.7, 0.85)
                Mood.SURPRISE_ME -> FillTarget()
            }
            is MoodChoice.Custom -> with(choice.mood) {
                FillTarget(
                    tempo = tempo.midpoint.takeIf { tempo.isEnabled },
                    energy = energy.midpoint.takeIf { energy.isEnabled },
                    valence = valence.midpoint.takeIf { valence.isEnabled },
                    halfTime = countHalfTime,
                )
            }
        }

        /** 0 for a perfect match; each unknown value counts as a middling miss. */
        internal fun fillDistance(c: CandidateTrack, target: FillTarget): Double {
            val f = c.features ?: return Double.MAX_VALUE
            var distance = 0.0
            target.tempo?.let { goal ->
                val deviation = if (target.halfTime) cadenceDeviation(f.tempo, goal) else f.tempo?.let { abs(it - goal) }
                distance += deviation?.let { it / 40 } ?: 0.5
            }
            target.energy?.let { goal -> distance += f.energy?.let { abs(it - goal) } ?: 0.5 }
            target.valence?.let { goal -> distance += f.valence?.let { abs(it - goal) } ?: 0.5 }
            return distance
        }

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
