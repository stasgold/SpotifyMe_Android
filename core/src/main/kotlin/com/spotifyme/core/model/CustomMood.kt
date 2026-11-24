package com.spotifyme.core.model

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A mood the user defines by setting every selection rule themselves. */
@Serializable
data class CustomMood(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "My Mood",
    /** Icon key (the iOS SF Symbol name; the Android UI maps it to a Material icon). */
    val symbol: String = "music.note",
    /** Index into the app's color palette. */
    val colorIndex: Int = 0,
    val tempo: Range = Range(min = 90.0, max = 130.0),
    /** Count a track at half the tempo as a match (e.g. 90 BPM for a 180 BPM target), as runners do. */
    val countHalfTime: Boolean = false,
    val energy: Range = Range(min = 0.4, max = 0.8),
    val valence: Range = Range(min = 0.4, max = 1.0),
    val danceability: Range = Range(min = 0.5, max = 1.0),
    val acousticness: Range = Range(min = 0.0, max = 0.5),
    val sources: Set<Source> = Source.entries.toSet(),
    val order: Order = Order.BEST_MATCH_FIRST,
    val targetCount: Int = 30,
    val maxPerArtist: Int = 2,
    /** Avoid the same artist twice in a row (applied after ordering, except for tempo/energy orders). */
    val spreadArtists: Boolean = true,
    /** Fetch recommendations aimed at these settings when too few tracks match. */
    val addRecommendationsWhenShort: Boolean = true,
) {
    /** An inclusive range of a feature. A disabled range accepts any value, including a missing one. */
    @Serializable
    data class Range(val isEnabled: Boolean = false, val min: Double, val max: Double) {
        fun contains(value: Double): Boolean = value in min(min, max)..max(min, max)
        val midpoint: Double get() = (min + max) / 2
        val halfWidth: Double get() = abs(max - min) / 2
    }

    /** Where tracks may come from. */
    @Serializable
    enum class Source(val title: String) {
        TOP_TRACKS("Your top tracks"),
        RECENTLY_PLAYED("Recently played"),
        LIKED("Liked Songs"),
        TOP_ARTISTS("More from your top artists"),
        NEW_MUSIC("New recommendations");

        internal val origins: Set<TrackOrigin>
            get() = when (this) {
                TOP_TRACKS -> setOf(TrackOrigin.TOP_SHORT_TERM, TrackOrigin.TOP_MEDIUM_TERM, TrackOrigin.TOP_LONG_TERM)
                RECENTLY_PLAYED -> setOf(TrackOrigin.RECENTLY_PLAYED)
                LIKED -> setOf(TrackOrigin.LIKED)
                TOP_ARTISTS -> setOf(TrackOrigin.ARTIST_SEARCH)
                NEW_MUSIC -> setOf(TrackOrigin.RECOMMENDATION)
            }
    }

    /** How the chosen tracks are ordered. */
    @Serializable
    enum class Order(val title: String) {
        BEST_MATCH_FIRST("Best match first"),
        SHUFFLED("Shuffled"),
        ENERGY_RISING("Energy builds up"),
        ENERGY_FALLING("Energy winds down"),
        TEMPO_RISING("Tempo speeds up"),
        TEMPO_FALLING("Tempo slows down"),
        WARM_UP_COOL_DOWN("Gentle start and finish"),
    }

    val displayName: String get() = name.trim().ifEmpty { "Custom Mood" }

    /** One-line summary of the active rules. */
    val summary: String
        get() {
            val parts = mutableListOf<String>()
            if (tempo.isEnabled) parts += "${tempo.min.toInt()}–${tempo.max.toInt()} BPM"
            fun pct(r: Range, label: String) {
                if (r.isEnabled) parts += "$label ${(r.min * 100).toInt()}–${(r.max * 100).toInt()}%"
            }
            pct(energy, "energy")
            pct(valence, "positivity")
            pct(danceability, "dance")
            pct(acousticness, "acoustic")
            return if (parts.isEmpty()) "Any track from your library" else parts.joinToString(" · ")
        }

    internal val allowedOrigins: Set<TrackOrigin> get() = sources.flatMap { it.origins }.toSet()

    /** True when the tempo window is enabled, so BPM fallbacks are worth looking up. */
    val needsTempo: Boolean get() = tempo.isEnabled
}

/** A built-in mood or one the user made. Drives generation, saving and display. */
sealed interface MoodChoice {
    data class BuiltIn(val mood: Mood) : MoodChoice
    data class Custom(val mood: CustomMood) : MoodChoice

    /** Stable ID, also used for the saved-playlist key (same scheme as the iOS app). */
    val id: String
        get() = when (this) {
            is BuiltIn -> mood.key
            is Custom -> "custom." + mood.id
        }

    val title: String
        get() = when (this) {
            is BuiltIn -> mood.title
            is Custom -> mood.displayName
        }

    val subtitle: String
        get() = when (this) {
            is BuiltIn -> mood.subtitle
            is Custom -> mood.summary
        }

    val playlistName: String get() = "SpotifyMe · $title"

    val playlistDescription: String
        get() = when (this) {
            is BuiltIn -> mood.playlistDescription
            is Custom -> "${mood.summary}. Custom mood made by SpotifyMe from your listening history."
        }
}
