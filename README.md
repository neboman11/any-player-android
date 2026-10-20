# Any Player Android

Any Player combines Spotify, Jellyfin and Plex in one Android music player. Spotify
playback controls a Spotify Connect device; Jellyfin and Plex audio streams locally
through Media3 ExoPlayer. Mixed queues, shuffle/repeat, custom STANDARD/UNION playlists,
distinct playback, search, sync, state/config import/export, and remote or on-device
AI DJ breaks share the same playback engine. Background playback exposes notification
controls and Android Auto media browsing.

The integrated v2 app uses Kotlin, Jetpack Compose, Media3, Hilt, Room and Coroutines/Flow.
See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for implementation and compatibility contracts.

## Module layout

| Module | Responsibility |
|---|---|
| `:core:model` | Pure Kotlin/JVM models, queue operations, run planning, DJ schedule, dedupe and normalization |
| `:core:data` | Repository/store contracts and implementations, Room, provider clients, auth, transfer; sync/startup contracts |
| `:playback` | Playback contract/engine, ExoPlayer and Spotify Connect backends, persistence, media service/session |
| `:dj` | DJ controller/coordinator, passages, artifact downloads, remote generation and on-device LLM/TTS |
| `:app` | Application/Activity, Compose screens and ViewModels, sync/startup implementations and cross-module Hilt wiring |

Dependency versions live in `gradle/libs.versions.toml`; Room schemas in `core/data/schemas`.

## Prerequisites

- JDK 21 (the Gradle daemon toolchain is pinned to 21).
- Android SDK: API 35 platform, build tools 35.0.0 and platform-tools.
- Android 8.0/API 26 or newer to run the app.
- Internet access for initial dependency downloads.

**No Rust toolchain, Cargo, Android NDK or sibling Rust checkout is required.** Native
LLM/TTS runtimes arrive as prebuilt dependencies. `:dj:downloadSherpaOnnxAar` downloads
the pinned sherpa-onnx AAR into gitignored `dj/libs/` on the first build and verifies its
SHA-256. DJ models/voices are downloaded separately through the app.

## Local configuration

Create `local.properties` at the repository root (do not commit it):

```properties
sdk.dir=/path/to/Android/Sdk
spotifyClientId=your_spotify_client_id
```

`spotifyClientId` is optional for compilation but required for Spotify sign-in. A Gradle
property overrides it: `-PspotifyClientId=...`. Register the Spotify redirect URI
`anyplayer://spotify-callback` for your client. Spotify playback requires a suitable
Spotify Connect device/account; the app does not decode Spotify audio locally.

## Build and run

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
adb shell am start -n com.anyplayer.android/.MainActivity
```

Select the intended target with `ANDROID_SERIAL` when multiple devices are attached
(and use `adb -s <serial>` for direct adb commands).

APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug builds bundle **arm64-v8a +
x86_64** only (64-bit phones and emulators); release builds retain the dependency ABIs.

## Tests and lint

All module unit tests (no device needed):

```bash
./gradlew :core:model:test :core:data:testDebugUnitTest :playback:testDebugUnitTest :dj:testDebugUnitTest :app:testDebugUnitTest
./gradlew --console=plain -q :app:lintDebug
```

Instrumented Room migration/repository tests and Android Auto session/sync tests (selected device/emulator required):

```bash
./gradlew :core:data:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

CI runs all five unit-test tasks, assembles debug, uploads the APK, runs app lint, and runs
`:app:connectedDebugAndroidTest` on an API 35 emulator in a parallel job.
See [CONTRIBUTING.md](CONTRIBUTING.md) for layering, threading and review conventions.

## Android Auto

The Media3 library service retains its legacy fully qualified name:
`com.anyplayer.android.feature.playback.service.AnyPlayerMediaLibraryService`.
Browse root id is `root`; its playable children are the current queue in play order,
identified by queue-entry uid. It is not a provider/library catalog tree. Validate
projection behavior with Android Auto Desktop Head Unit (DHU) or compatible hardware;
Spotify-only playback also keeps the media playback foreground service alive.

## Existing installs

V2 upgrades in place (`com.anyplayer.android`, versionCode 2), preserving playlists,
provider credentials, sync settings, DJ files and export formats. Room migrates
`any_player.db` from v1/v2 to v3 without destructive fallback; the encrypted legacy
queue is migrated once into Room plus plain non-secret playback settings. Restored
playback is paused and does not take over a Spotify device until Play.

Install with the same signing key as the existing app. Do not uninstall or clear app
data to upgrade: those actions remove local data. A signature mismatch requires the
original signing key, not a database reset.
