package com.spotifyme.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.spotifyme.app.AppContainer

@Composable
fun AppRoot(container: AppContainer) {
    val isConfigured by container.isConfigured.collectAsStateWithLifecycle()
    val isSignedIn by container.isSignedIn.collectAsStateWithLifecycle()
    val errorMessage by container.errorMessage.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !isConfigured -> SetupScreen(container)
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
    fun openEditor(moodId: String?) = nav.navigate(if (moodId == null) "editor" else "editor?id=${Uri.encode(moodId)}")

    NavHost(navController = nav, startDestination = "moods") {
        composable("moods") {
            MoodGridScreen(
                container,
                onMood = { nav.navigate("playlist/${Uri.encode(it.id)}") },
                onNewMood = { openEditor(null) },
                onEditMood = { openEditor(it.id) },
                onAbout = { nav.navigate("about") },
            )
        }
        composable("playlist/{choice}", arguments = listOf(navArgument("choice") { type = NavType.StringType })) { entry ->
            val choiceId = entry.arguments?.getString("choice").orEmpty()
            val model = viewModel { PlaylistViewModel(choiceId, container) }
            PlaylistScreen(model, onBack = { nav.popBackStack() }, onEdit = { openEditor(it.id) })
        }
        composable(
            "editor?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            MoodEditorScreen(container, moodId = entry.arguments?.getString("id")) { deleted ->
                // A deleted mood's playlist screen has nothing left to show.
                if (deleted) nav.popBackStack("moods", inclusive = false) else nav.popBackStack()
            }
        }
        composable("about") {
            AboutScreen(container, onBack = { nav.popBackStack() }, onSetup = { nav.navigate("setup") })
        }
        composable("setup") {
            SetupScreen(container, onBack = { nav.popBackStack() })
        }
    }
}
