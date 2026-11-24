package com.spotifyme.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.spotifyme.app.AppConfig
import com.spotifyme.app.AppContainer

/**
 * Lets each person enter their own Spotify Client ID (and optional GetSongBPM key) on the device.
 * Shown on first launch, and from About to change the values ([onBack] non-null).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(container: AppContainer, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var clientId by rememberSaveable { mutableStateOf(container.spotifyClientId) }
    var bpmKey by rememberSaveable { mutableStateOf(container.getSongBpmApiKey) }
    var copied by remember { mutableStateOf(false) }
    val trimmedId = clientId.trim()
    val isValid = AppConfig.isValidClientId(trimmedId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (onBack == null) "Welcome" else "Spotify app") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Set up Spotify", style = MaterialTheme.typography.titleMedium)
                    Text("Each person uses their own free Spotify developer app.")
                    Text("1. Open the Spotify Developer Dashboard and create an app.")
                    Text(bold("2. Add this Redirect URI and tick ", "Web API", ":"))
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Redirect URI", AppConfig.REDIRECT_URI))
                        copied = true
                    }) {
                        Icon(if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (copied) "Copied" else AppConfig.REDIRECT_URI, fontFamily = FontFamily.Monospace)
                    }
                    Text(bold("3. Under ", "User Management", ", add your Spotify account's email."))
                    Text(bold("4. Copy the app's ", "Client ID", " and paste it below."))
                    TextButton(onClick = { runCatching { uriHandler.openUri(AppConfig.DASHBOARD_URL) } }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Spotify Developer Dashboard")
                    }
                }
            }

            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Spotify Client ID") },
                placeholder = { Text("32-character Client ID") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                isError = trimmedId.isNotEmpty() && !isValid,
                supportingText = {
                    Text(
                        if (trimmedId.isNotEmpty() && !isValid) "A Client ID is 32 letters and digits (0–9, a–f)."
                        else "No client secret is needed. It's stored only on this phone."
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = bpmKey,
                onValueChange = { bpmKey = it },
                label = { Text("GetSongBPM API key") },
                placeholder = { Text("Optional") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                supportingText = { Text("Improves BPM matching for the running moods.") },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { runCatching { uriHandler.openUri("https://getsongbpm.com/api") } }) {
                Text("Get a free key at getsongbpm.com")
            }

            Button(
                onClick = {
                    container.configure(trimmedId, bpmKey)
                    onBack?.invoke()
                },
                enabled = isValid,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun bold(before: String, strong: String, after: String) = buildAnnotatedString {
    append(before)
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(strong) }
    append(after)
}
