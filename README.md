# SpotifyMe (Android)

The Android port of [SpotifyMe for iOS](https://github.com/stasgold/SpotifyMe). It reads your Spotify listening history and builds mood playlists, then saves them to your Spotify account:

| Mood | What it picks | Order |
|---|---|---|
| **Slow Down** | 60–100 BPM, low energy, acoustic-leaning | Winds down: calmest track last |
| **Speed Up** | 100–150 BPM, energy ≥ 0.6 | Tempo and energy climb |
| **Running 180 bpm** | 175–185 BPM, or 87–93 BPM (half-time); energy ≥ 0.65 | Gentlest tracks first and last |
| **Running 170 bpm** | 165–175 BPM, or 82–88 BPM (half-time); energy ≥ 0.65 | Same as above |
| **Feeling Good** | Positivity (valence) ≥ 0.65, energy 0.5–0.85, danceability ≥ 0.6 | No artist twice in a row |
| **Surprise Me** | ~40% "forgotten favorites" (all-time top tracks you haven't played lately) + ~60% new recommendations | Shuffled |

Each playlist has about 30 tracks, with at most 2 per artist. For a running mood, if there aren't enough tracks at ±5 BPM, the window widens to ±8 BPM. If a mood still has fewer than 20 tracks, the app adds recommendations aimed at that mood's tempo and energy. Saving a mood again **updates the same playlist** rather than creating a duplicate.

The mood rules, data sources and behavior are the same as the iOS app; only the UI layer is Android-specific.

## How it works

```
Spotify (your history) ──► track pool ──► audio features ──► mood rules ──► save playlist to Spotify
  top tracks (3 ranges)                    1. ReccoBeats (tempo, energy, valence, …)
  recently played                          2. GetSongBPM (BPM only; running moods)
  Liked Songs (200)                        3. Deezer by ISRC (BPM only; running moods)
  top artists → search
  ReccoBeats recommendations
```

Spotify no longer gives new apps tempo/mood data (audio features) or recommendations. Those were removed in November 2024, so this app gets them from the free services above. Results are cached on the device (`files/SpotifyMe/features.json`), so each track is only looked up once.

### Project layout

```
core/                              Kotlin/JVM module with all non-UI logic (port of SpotifyMeCore)
  model/      Track, AudioFeatures, Mood
  engine/     MoodEngine (mood rules), PlaylistService, FeatureCache
  api/        Spotify, ReccoBeats, GetSongBPM, Deezer clients
  src/test/   unit tests for the mood rules and the networking plumbing
app/                               the Android app (Jetpack Compose, Android 8.0+)
  auth/       Spotify sign-in (authorization code + PKCE in a Custom Tab), Keystore-encrypted tokens
  ui/         login, mood grid, playlist preview/save, about
```

| iOS | Android |
|---|---|
| SwiftUI | Jetpack Compose + Material 3 |
| `ASWebAuthenticationSession` | Custom Tab + `spotifyme://callback` intent filter |
| Keychain | AES-GCM key in the Android Keystore |
| `UserDefaults` | `SharedPreferences` |
| Swift `actor`s | Kotlin coroutines + `Mutex` |
| `Config.xcconfig` | `local.properties` |

## Setup

You need Android Studio (or the Android SDK and JDK 17+).

1. **Use your Spotify app** from <https://developer.spotify.com/dashboard>. If you already set it up for the iOS app, the same Client ID works: the Android app uses the same redirect URI.
   - Redirect URI: `spotifyme://callback`
   - API: tick **Web API** (and **Android** if Spotify asks for a platform)
   - Copy the **Client ID**. No client secret is needed: the app uses PKCE.
   - Under **User Management**, add the Spotify email address of everyone who will use the app (including yourself).
2. *(Optional)* Get a free GetSongBPM API key at <https://getsongbpm.com/api>. It improves BPM coverage for the running moods.
3. Configure `local.properties` in the project root (git-ignored; Android Studio creates it with `sdk.dir`):
   ```properties
   spotify.clientId=YOUR_CLIENT_ID
   getsongbpm.apiKey=YOUR_KEY            # optional
   app.applicationId=com.yourname.spotifyme  # optional, default com.stasgold.spotifyme
   ```
   You can use the environment variables `SPOTIFY_CLIENT_ID`, `GETSONGBPM_API_KEY` and `APP_APPLICATION_ID` instead.
4. Open the folder in Android Studio and press **Run**, or from a terminal:
   ```sh
   ./gradlew :app:installDebug
   ```

Run the unit tests with `./gradlew test`.

## Continuous integration

`.github/workflows/android.yml` runs the unit tests and builds the debug and release APKs on every push. Add a `SPOTIFY_CLIENT_ID` repository secret (and optionally `GETSONGBPM_API_KEY`) so the built APKs can sign in; without it they show a "Client ID missing" screen.

To get a signed release APK, add these secrets too: `ANDROID_KEYSTORE_BASE64` (your keystore, base64-encoded), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Locally, put `storeFile`, `storePassword`, `keyAlias` and `keyPassword` in a git-ignored `keystore.properties`.

The APKs are attached to each workflow run under **Artifacts**. To install one on your phone, download the debug APK, open it, and allow "Install unknown apps" for your browser or file manager when asked.

## Spotify limits to know about

Since February/March 2026, apps in Spotify's **Development Mode** have these restrictions:
- The app owner needs an active **Spotify Premium** subscription.
- At most **5 authorized users** per app, added under User Management.
- One Development Mode Client ID per developer.
- Some endpoints were removed or renamed. The app already uses the new ones:
  - `POST /me/playlists` to create playlists
  - `/playlists/{id}/items` to change playlist contents
  - Search instead of the removed "artist top tracks" endpoint

If saving a playlist fails with a 400 or 403 error, check Spotify's
[February 2026 migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide)
and compare the request body in `SpotifyApi.replacePlaylistItems` (`{"uris": [...]}`).

## Third-party data terms

- **ReccoBeats**: free, no key, rate-limited. The app waits and retries automatically.
- **GetSongBPM**: free, but a visible link back to GetSongBPM.com is **required**. It's on the About screen.
- **Deezer**: free public catalog lookups. A BPM of 0 means "unknown" and is ignored.
