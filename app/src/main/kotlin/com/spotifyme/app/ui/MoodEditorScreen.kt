package com.spotifyme.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spotifyme.app.AppContainer
import com.spotifyme.core.model.CustomMood
import kotlin.math.roundToInt

/**
 * Create ([moodId] null) or edit a custom mood, setting every selection rule.
 * [onDone] gets true when the mood was deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodEditorScreen(container: AppContainer, moodId: String?, onDone: (deleted: Boolean) -> Unit) {
    val existing = remember(moodId) { moodId?.let(container::customMood) }
    val isNew = existing == null
    var mood by remember(moodId) {
        mutableStateOf(existing ?: CustomMood(colorIndex = container.customMoods.value.size))
    }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New Mood" else "Edit Mood") },
                navigationIcon = {
                    IconButton(onClick = { onDone(false) }) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            container.saveCustomMood(mood)
                            onDone(false)
                        },
                        enabled = mood.sources.isNotEmpty(),
                    ) { Text("Save", fontWeight = FontWeight.Bold) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            // Name and look
            Header("Name and look")
            OutlinedTextField(
                value = mood.name,
                onValueChange = { mood = mood.copy(name = it) },
                label = { Text("Mood name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(MoodIcons.all) { (key, icon) ->
                    val selected = mood.symbol == key
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) Brush.linearGradient(MoodPalette.gradient(mood.colorIndex))
                                else Brush.linearGradient(List(2) { MaterialTheme.colorScheme.surfaceVariant })
                            )
                            .clickable { mood = mood.copy(symbol = key) }
                            .semantics { contentDescription = key },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, null, tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MoodPalette.colors.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(MoodPalette.gradient(index)))
                            .clickable { mood = mood.copy(colorIndex = index) }
                            .semantics { contentDescription = "Color ${index + 1}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (mood.colorIndex == index) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Tempo
            Header("Tempo")
            SwitchRow("Filter by tempo", checked = mood.tempo.isEnabled) {
                mood = mood.copy(tempo = mood.tempo.copy(isEnabled = it))
            }
            if (mood.tempo.isEnabled) {
                Text(
                    "${mood.tempo.min.roundToInt()} – ${mood.tempo.max.roundToInt()} BPM",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                RangeSlider(
                    value = mood.tempo.min.toFloat()..mood.tempo.max.toFloat(),
                    onValueChange = { r -> mood = mood.copy(tempo = mood.tempo.copy(min = r.start.roundToInt().toDouble(), max = r.endInclusive.roundToInt().toDouble())) },
                    valueRange = 40f..220f,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                SwitchRow("Count half-time tempo", checked = mood.countHalfTime) { mood = mood.copy(countHalfTime = it) }
                Footer(
                    if (mood.countHalfTime) "A track at half the tempo also matches (e.g. 90 BPM for 180), which suits running cadence."
                    else "Only tracks whose BPM is inside this range are used."
                )
            }

            // Sound
            Header("Sound")
            PercentRangeRow("Energy", "Calm → intense", mood.energy) { mood = mood.copy(energy = it) }
            PercentRangeRow("Positivity", "Sad, dark → happy, cheerful", mood.valence) { mood = mood.copy(valence = it) }
            PercentRangeRow("Danceability", "Free-flowing → steady, danceable beat", mood.danceability) { mood = mood.copy(danceability = it) }
            PercentRangeRow("Acousticness", "Electronic → acoustic", mood.acousticness) { mood = mood.copy(acousticness = it) }
            Footer("Turn a filter on to require it. Tracks closest to the middle of each range rank highest. Tracks missing data for an active filter are skipped.")

            // Sources
            Header("Pick tracks from")
            CustomMood.Source.entries.forEach { source ->
                val checked = source in mood.sources
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { mood = mood.copy(sources = if (checked) mood.sources - source else mood.sources + source) }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text(source.title)
                }
            }
            if (mood.sources.isEmpty()) {
                Footer("Choose at least one source.", color = MaterialTheme.colorScheme.error)
            }

            // Playlist
            Header("Playlist")
            StepperRow("Tracks: ${mood.targetCount}", mood.targetCount, 5..100, step = 5) { mood = mood.copy(targetCount = it) }
            StepperRow("Max per artist: ${mood.maxPerArtist}", mood.maxPerArtist, 1..10, step = 1) { mood = mood.copy(maxPerArtist = it) }
            OrderRow(mood.order) { mood = mood.copy(order = it) }
            if (mood.order == CustomMood.Order.BEST_MATCH_FIRST || mood.order == CustomMood.Order.SHUFFLED) {
                SwitchRow("Avoid same artist back to back", checked = mood.spreadArtists) { mood = mood.copy(spreadArtists = it) }
            }
            if (CustomMood.Source.NEW_MUSIC in mood.sources) {
                SwitchRow("Add recommendations if too few match", checked = mood.addRecommendationsWhenShort) {
                    mood = mood.copy(addRecommendationsWhenShort = it)
                }
            }

            if (!isNew) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) { Text("Delete mood", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${mood.displayName}\"?") },
            text = { Text("The playlist already saved in Spotify is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    container.deleteCustomMood(mood)
                    onDone(true)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Footer(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A 0–100% range filter with an on/off switch. */
@Composable
private fun PercentRangeRow(title: String, detail: String, range: CustomMood.Range, onChange: (CustomMood.Range) -> Unit) {
    SwitchRow(
        title = title,
        subtitle = if (range.isEnabled) "${(range.min * 100).roundToInt()}% – ${(range.max * 100).roundToInt()}%" else detail,
        checked = range.isEnabled,
    ) { onChange(range.copy(isEnabled = it)) }
    if (range.isEnabled) {
        RangeSlider(
            value = range.min.toFloat()..range.max.toFloat(),
            onValueChange = { r ->
                // 5% steps, like the iOS sliders.
                fun snap(v: Float) = (v * 20).roundToInt() / 20.0
                onChange(range.copy(min = snap(r.start), max = snap(r.endInclusive)))
            },
            valueRange = 0f..1f,
            steps = 19,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun StepperRow(label: String, value: Int, bounds: IntRange, step: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onChange((value - step).coerceIn(bounds)) }, enabled = value > bounds.first) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease")
            }
            IconButton(onClick = { onChange((value + step).coerceIn(bounds)) }, enabled = value < bounds.last) {
                Icon(Icons.Filled.Add, contentDescription = "Increase")
            }
        }
    }
}

@Composable
private fun OrderRow(order: CustomMood.Order, onChange: (CustomMood.Order) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Order", modifier = Modifier.weight(1f))
            Text(order.title, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CustomMood.Order.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                    trailingIcon = { if (option == order) Icon(Icons.Filled.Check, null) },
                )
            }
        }
    }
}
