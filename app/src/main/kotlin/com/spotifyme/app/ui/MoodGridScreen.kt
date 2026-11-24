package com.spotifyme.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifyme.app.AppContainer
import com.spotifyme.core.model.CustomMood
import com.spotifyme.core.model.Mood
import com.spotifyme.core.model.MoodChoice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodGridScreen(
    container: AppContainer,
    onMood: (MoodChoice) -> Unit,
    onNewMood: () -> Unit,
    onEditMood: (CustomMood) -> Unit,
    onAbout: () -> Unit,
) {
    val customMoods by container.customMoods.collectAsStateWithLifecycle()
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
            items(Mood.entries, key = { it.key }) { mood ->
                val choice = MoodChoice.BuiltIn(mood)
                MoodTile(choice, onClick = { onMood(choice) })
            }
            items(customMoods, key = { "custom." + it.id }) { custom ->
                CustomMoodTile(
                    custom,
                    onClick = { onMood(MoodChoice.Custom(custom)) },
                    onEdit = { onEditMood(custom) },
                    onDelete = { container.deleteCustomMood(custom) },
                )
            }
            item(key = "new") { NewMoodTile(onClick = onNewMood) }
        }
    }
}

@Composable
private fun MoodTile(choice: MoodChoice, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(choice.gradient))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(choice.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        Spacer(Modifier.weight(1f, fill = false).heightIn(min = 24.dp))
        Text(
            choice.title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            choice.subtitle,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Tap to open; long-press for Edit and Delete (the iOS context menu). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CustomMoodTile(mood: CustomMood, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        MoodTile(
            MoodChoice.Custom(mood),
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }, onLongClickLabel = "Edit or delete"),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.Filled.Tune, null) },
                onClick = {
                    menu = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun NewMoodTile(onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .clip(RoundedCornerShape(20.dp))
            .drawBehind {
                val stroke = 2.dp.toPx()
                drawRoundRect(
                    color = tint,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))),
                )
            }
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Filled.AddCircle, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
        Text("New mood", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tint)
        Text("Set every parameter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
