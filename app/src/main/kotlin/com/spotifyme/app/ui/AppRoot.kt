package com.spotifyme.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.spotifyme.app.AppConfig
import com.spotifyme.app.AppContainer
import com.spotifyme.core.model.Mood

@Composable
fun AppRoot(container: AppContainer) {
    val isSignedIn by container.isSignedIn.collectAsStateWithLifecycle()
    val errorMessage by container.errorMessage.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !AppConfig.isConfigured -> MissingConfigScreen()
            isSignedIn -> MainNavigation(container)
            else -> LoginScreen(container)
        }
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = container::dismissError,
            title = { Text("Something went wrong") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = container::dismissError) { Text("OK") } },
        )
    }
}

@Composable
private fun MainNavigation(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "moods") {
        composable("moods") {
            MoodGridScreen(
                onMood = { nav.navigate("playlist/${it.key}") },
                onAbout = { nav.navigate("about") },
            )
        }
        composable("playlist/{mood}", arguments = listOf(navArgument("mood") { type = NavType.StringType })) { entry ->
            val mood = Mood.fromKey(entry.arguments?.getString("mood").orEmpty()) ?: Mood.SLOW_DOWN
            val model = viewModel { PlaylistViewModel(mood, container) }
            PlaylistScreen(model, onBack = { nav.popBackStack() })
        }
        composable("about") {
            AboutScreen(container, onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun MissingConfigScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(56.dp))
        Text("Spotify Client ID missing", style = MaterialTheme.typography.titleLarge)
        Text(
            "Add spotify.clientId=<your Client ID> to local.properties (or set SPOTIFY_CLIENT_ID) and rebuild. See README.md.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
