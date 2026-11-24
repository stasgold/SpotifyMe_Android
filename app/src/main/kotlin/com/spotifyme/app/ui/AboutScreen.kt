package com.spotifyme.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.spotifyme.app.AppContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(container: AppContainer, onBack: () -> Unit, onSetup: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("Data sources")
            LinkRow("Listening history and playlists: Spotify", "https://www.spotify.com") { uriHandler.openUri(it) }
            LinkRow("Audio features: ReccoBeats", "https://reccobeats.com") { uriHandler.openUri(it) }
            // Required by GetSongBPM's API terms: a visible link back to their site.
            LinkRow("BPM data by GetSongBPM.com", "https://getsongbpm.com") { uriHandler.openUri(it) }
            LinkRow("BPM data: Deezer", "https://www.deezer.com") { uriHandler.openUri(it) }

            SectionHeader("Spotify app")
            ListItem(
                headlineContent = { Text("Client ID") },
                trailingContent = {
                    val id = container.spotifyClientId
                    Text(if (id.length > 8) "…" + id.takeLast(6) else id, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
            ListItem(
                headlineContent = { Text("Change Client ID or API keys", color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable(onClick = onSetup),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            ListItem(
                headlineContent = { Text("Clear cached track data", color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable {
                    scope.launch {
                        container.clearCache()
                        Toast.makeText(context, "Cached track data cleared", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            ListItem(
                headlineContent = { Text("Sign out of Spotify", color = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { container.signOut() },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LinkRow(label: String, url: String, open: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(label, color = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
        modifier = Modifier.clickable { runCatching { open(url) } },
    )
}
