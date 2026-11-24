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

Each playlist has about 30 tracks, with at most 2 per artist. For a running mood, if there aren't enough tracks at ±5 BPM, the window widens to ±8 BPM. If a mood has fewer tracks than its full count, the app fetches recommendations aimed at that mood's tempo and energy (seeded by your own tracks), and if it's still short, fills the remaining slots with the recommended tracks closest to the mood. Recommended tracks are marked "Recommended" in the list. (Spotify's own recommendations API is closed to new apps, so these come from ReccoBeats, which returns Spotify tracks.) Saving a mood again **updates the same playlist** rather than creating a duplicate.

### Custom moods

Tap **New mood** at the end of the grid to make your own mood and set every rule:
- **Name and look**: name, icon and color.
- **Tempo**: a BPM range, optionally counting half-time tempo (e.g. 90 BPM for 180), as runners do.
- **Sound**: ranges for energy, positivity, danceability and acousticness. Only the filters you turn on are applied, and tracks closest to the middle of each range rank highest.
- **Pick tracks from**: your top tracks, recently played, Liked Songs, more from your top artists, new recommendations.
- **Playlist**: number of tracks, max per artist, order (best match, shuffled, energy or tempo rising/falling, gentle start and finish), avoiding the same artist back to back, and filling up with recommendations when too few tracks match (needs "New recommendations" as a source).

Long-press a custom mood to edit or delete it, or tap **Edit** on its playlist screen.

The mood rules, data sources and behavior are the same as the iOS app; only the UI layer is Android-specific.

## 📲 Install on your phone (sideload)

**[Download SpotifyMe.apk (latest build)](https://github.com/stasgold/SpotifyMe_Android/releases/latest/download/SpotifyMe.apk)**: open this link on your Android phone, open the downloaded file, and allow "Install unknown apps" for your browser when asked. Newer builds install over older ones. On first launch the app asks for a Spotify **Client ID** and shows how to get one (see [Setup](#setup)).

## How it works

```
Spotify (your history) ──► track pool ──► audio features ──► mood rules ──► save playlist to Spotify
  top tracks (3 ranges)                    1. ReccoBeats (tempo, energy, valence, …)
  recently played                          2. GetSongBPM (BPM only; running moods)
  Liked Songs (200)                        3. Deezer by ISRC (BPM only; running moods)
  top artists → search
  ReccoBeats recommendations
```

Spotify no longer gives new apps tempo/mood data (audio features) or recommendations. Those were removed in November 2024, so this app gets them from the free services above (BPM data by [GetSongBPM.com](https://getsongbpm.com)). Results are cached on the device (`files/SpotifyMe/features.json`), so each track is only looked up once.

### Project layout

```
core/                              Kotlin/JVM module with all non-UI logic (port of SpotifyMeCore)
  model/      Track, AudioFeatures, Mood, CustomMood
  engine/     MoodEngine (mood rules), PlaylistService, FeatureCache
  api/        Spotify, ReccoBeats, GetSongBPM, Deezer clients
  src/test/   unit tests for the mood rules and the networking plumbing
app/                               the Android app (Jetpack Compose, Android 8.0+)
  auth/       Spotify sign-in (authorization code + PKCE in a Custom Tab), Keystore-encrypted tokens
  ui/         setup, login, mood grid, mood editor, playlist preview/save, about
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
   These values are optional defaults: on first launch the app asks for a Client ID (and an optional GetSongBPM key) and stores them on the phone, so each person can use their own Spotify developer app. Change them later under **About → Change Client ID or API keys**.
4. Open the folder in Android Studio and press **Run**, or from a terminal:
   ```sh
   ./gradlew :app:installDebug
   ```

Run the unit tests with `./gradlew test`.

## Continuous integration

`.github/workflows/android.yml` runs the unit tests and builds the debug and release APKs on every push. You can add a `SPOTIFY_CLIENT_ID` repository secret (and optionally `GETSONGBPM_API_KEY`) to build them in as defaults; without it, the app asks for a Client ID on first launch.

To get a signed release APK, add these secrets too: `ANDROID_KEYSTORE_BASE64` (your keystore, base64-encoded), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Locally, put `storeFile`, `storePassword`, `keyAlias` and `keyPassword` in a git-ignored `keystore.properties`.

Every push to `main` also publishes the debug APK as the latest GitHub Release (`SpotifyMe.apk`), which is what the download link above points to. The APKs are attached to each workflow run under **Artifacts** too.

Debug builds are signed with the committed `app/debug.keystore` (standard `android` passwords, not a secret), so an APK built on any machine or in CI installs over another.

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
- **GetSongBPM**: free, but a visible link back to GetSongBPM.com is **required**. It's on the About screen and here: BPM data by [GetSongBPM.com](https://getsongbpm.com).
- **Deezer**: free public catalog lookups. A BPM of 0 means "unknown" and is ignored.
