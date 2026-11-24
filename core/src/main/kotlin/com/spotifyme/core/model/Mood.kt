package com.spotifyme.core.model

enum class Mood(
    /** Stable key, the same as the iOS app's raw values. */
    val key: String,
    val title: String,
    val subtitle: String,
) {
    SLOW_DOWN("slowDown", "Slow Down", "Calm, low-energy tracks that wind down"),
    SPEED_UP("speedUp", "Speed Up", "Tempo and energy that keep building"),
    RUNNING_180("running180", "Running 180 bpm", "Matched to 180 steps per minute"),
    RUNNING_170("running170", "Running 170 bpm", "Matched to 170 steps per minute"),
    FEELING_GOOD("feelingGood", "Feeling Good", "Upbeat, happy and danceable"),
    SURPRISE_ME("surpriseMe", "Surprise Me", "Forgotten favorites and new finds");

    val playlistName: String get() = "SpotifyMe · $title"

    val playlistDescription: String get() = "$subtitle. Made by SpotifyMe from your listening history."

    /** Target running cadence, if this is a running mood. */
    val cadence: Double?
        get() = when (this) {
            RUNNING_180 -> 180.0
            RUNNING_170 -> 170.0
            else -> null
        }

    companion object {
        fun fromKey(key: String): Mood? = entries.firstOrNull { it.key == key }
    }
}
