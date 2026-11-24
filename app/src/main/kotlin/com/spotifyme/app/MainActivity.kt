package com.spotifyme.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.spotifyme.app.ui.AppRoot
import com.spotifyme.app.ui.theme.SpotifyMeTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as SpotifyMeApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh launch, so a rotation does not replay an old redirect.
        if (savedInstanceState == null) handleRedirect(intent)
        setContent {
            SpotifyMeTheme {
                AppRoot(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    /** Spotify sends the browser back to spotifyme://callback?code=...&state=... */
    private fun handleRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.toString().startsWith(AppConfig.REDIRECT_URI)) {
            container.completeSignIn(data)
            intent.data = null
        }
    }
}
