# Any Player Android

Android companion app for **Any Player**, built with Kotlin + Jetpack Compose + Media3.

This app targets phone/tablet and Android Auto-style media browsing, and includes provider integrations (Spotify/Jellyfin/Plex), local playlist persistence, and background media playback.

The v2 rewrite is in progress; the design it is built from is `docs/ARCHITECTURE.md`.

## Tech Stack

- Kotlin + Jetpack Compose
- Media3 (`ExoPlayer`, `MediaSession`, `MediaLibraryService`)
- Hilt (DI)
- Room (local storage)
- Coroutines + Flow

## Project Layout

- `core/model/` — pure Kotlin/JVM domain models, queue ops, run planning, dedupe, normalization
- `core/data/` — Room database (`any_player.db`, schemas in `core/data/schemas`), secure stores
- `playback/` — playback engine, ExoPlayer backend, MediaLibraryService + session player
- `dj/` — AI DJ (on-device LLM/TTS dependencies, espeak-ng data)
- `app/` — Android application module
- `docs/ARCHITECTURE.md` — architecture of the v2 rewrite

Dependency versions live in `gradle/libs.versions.toml`.

## Prerequisites

- Android SDK (API 35)
- JDK 21+

The sherpa-onnx TTS AAR has no Maven artifact; `:dj:downloadSherpaOnnxAar` fetches it into `dj/libs/` (gitignored) on the first build and verifies its SHA-256.

## Local Configuration

Create/update `local.properties` in the repo root:

- `sdk.dir=/path/to/Android/Sdk` (required)
- `spotifyClientId=<your_spotify_client_id>` (optional at build time; needed for Spotify auth flow)

You can also pass `spotifyClientId` as a Gradle property: `-PspotifyClientId=...`

## Build / Install

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## Testing

JVM unit tests (queue ops, run planning, dedupe fixture, normalization):

```bash
./gradlew :core:model:test
```

Instrumented Room migration tests (device/emulator required):

```bash
./gradlew :core:data:connectedDebugAndroidTest
```

## Notes

- Media browsing is exposed via `com.anyplayer.android.feature.playback.service.AnyPlayerMediaLibraryService` (browse root id `root`).
- OAuth deep-link callback for Spotify uses: `anyplayer://spotify-callback`.
- The current Exo playback slice follows the v2 contracts: load-tagged item events, queue edits
  that preserve the playing item, and volume/shuffle/repeat controls. Restore is immediate;
  normalization and pending DJ breaks are stored only until the full engine lands.
