package com.spotifyme.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.spotifyme.core.model.Mood
import com.spotifyme.core.model.MoodChoice

/**
 * Icons a custom mood can use. Keys are the iOS app's SF Symbol names, so a mood means the same
 * thing on both platforms; each maps to the closest Material icon.
 */
object MoodIcons {
    val all: List<Pair<String, ImageVector>> = listOf(
        "music.note" to Icons.Filled.MusicNote,
        "headphones" to Icons.Filled.Headphones,
        "figure.run" to Icons.AutoMirrored.Filled.DirectionsRun,
        "figure.walk" to Icons.AutoMirrored.Filled.DirectionsWalk,
        "bicycle" to Icons.AutoMirrored.Filled.DirectionsBike,
        "dumbbell.fill" to Icons.Filled.FitnessCenter,
        "moon.stars.fill" to Icons.Filled.Bedtime,
        "sun.max.fill" to Icons.Filled.WbSunny,
        "bolt.fill" to Icons.Filled.Bolt,
        "heart.fill" to Icons.Filled.Favorite,
        "flame.fill" to Icons.Filled.LocalFireDepartment,
        "leaf.fill" to Icons.Filled.Eco,
        "cup.and.saucer.fill" to Icons.Filled.LocalCafe,
        "car.fill" to Icons.Filled.DirectionsCar,
        "book.fill" to Icons.AutoMirrored.Filled.MenuBook,
        "party.popper.fill" to Icons.Filled.Celebration,
        "sparkles" to Icons.Filled.AutoAwesome,
        "cloud.rain.fill" to Icons.Filled.Umbrella,
    )
    private val byKey = all.toMap()

    fun icon(key: String): ImageVector = byKey[key] ?: Icons.Filled.MusicNote
}

/** Tile colors for custom moods (the iOS app's palette, in iOS system colors). */
object MoodPalette {
    private val blue = Color(0xFF007AFF)
    val colors: List<List<Color>> = listOf(
        listOf(blue, Color(0xFF32ADE6)),                 // blue, cyan
        listOf(Color(0xFF34C759), Color(0xFF00C7BE)),    // green, mint
        listOf(Color(0xFFFF9500), Color(0xFFFF3B30)),    // orange, red
        listOf(Color(0xFFFF2D55), Color(0xFFAF52DE)),    // pink, purple
        listOf(Color(0xFF5856D6), blue),                 // indigo, blue
        listOf(Color(0xFFFFCC00), Color(0xFFFF9500)),    // yellow, orange
        listOf(Color(0xFF30B0C7), Color(0xFF34C759)),    // teal, green
        listOf(Color(0xFF8E8E93), Color(0xFF000000)),    // gray, black
    )

    fun gradient(index: Int): List<Color> = colors[((index % colors.size) + colors.size) % colors.size]
}

val MoodChoice.icon: ImageVector
    get() = when (this) {
        is MoodChoice.BuiltIn -> mood.icon
        is MoodChoice.Custom -> MoodIcons.icon(mood.symbol)
    }

val MoodChoice.gradient: List<Color>
    get() = when (this) {
        is MoodChoice.BuiltIn -> mood.gradient
        is MoodChoice.Custom -> MoodPalette.gradient(mood.colorIndex)
    }

val Mood.icon: ImageVector
    get() = when (this) {
        Mood.SLOW_DOWN -> Icons.Filled.Bedtime
        Mood.SPEED_UP -> Icons.Filled.Bolt
        Mood.RUNNING_180, Mood.RUNNING_170 -> Icons.AutoMirrored.Filled.DirectionsRun
        Mood.FEELING_GOOD -> Icons.Filled.WbSunny
        Mood.SURPRISE_ME -> Icons.Filled.AutoAwesome
    }

/** Same color pairs as the iOS app (iOS system colors). */
val Mood.gradient: List<Color>
    get() = when (this) {
        Mood.SLOW_DOWN -> listOf(Color(0xFF5856D6), Color(0xFF007AFF))
        Mood.SPEED_UP -> listOf(Color(0xFFFF9500), Color(0xFFFF3B30))
        Mood.RUNNING_180 -> listOf(Color(0xFF34C759), Color(0xFF30B0C7))
        Mood.RUNNING_170 -> listOf(Color(0xFF30B0C7), Color(0xFF32ADE6))
        Mood.FEELING_GOOD -> listOf(Color(0xFFFFCC00), Color(0xFFFF9500))
        Mood.SURPRISE_ME -> listOf(Color(0xFFAF52DE), Color(0xFFFF2D55))
    }
