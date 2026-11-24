package com.spotifyme.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotifyme.core.model.Mood

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodGridScreen(onMood: (Mood) -> Unit, onAbout: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Pick a mood") },
                actions = {
                    IconButton(onClick = onAbout) { Icon(Icons.Outlined.Info, contentDescription = "About") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(Mood.entries) { mood -> MoodTile(mood, onClick = { onMood(mood) }) }
        }
    }
}

@Composable
private fun MoodTile(mood: Mood, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(mood.gradient))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(mood.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        Spacer(Modifier.weight(1f, fill = false).heightIn(min = 24.dp))
        Text(mood.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            mood.subtitle,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

val Mood.icon: ImageVector
    get() = when (this) {
        Mood.SLOW_DOWN -> Icons.Filled.Bedtime
        Mood.SPEED_UP -> Icons.Filled.Bolt
        Mood.RUNNING_180 -> Icons.AutoMirrored.Filled.DirectionsRun
        Mood.RUNNING_170 -> Icons.AutoMirrored.Filled.DirectionsRun
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
