# Any Player Android

Android companion app for **Any Player**, built with Kotlin + Jetpack Compose + Media3.

This app targets phone/tablet and Android Auto-style media browsing, and includes provider integrations (Spotify/Jellyfin/Plex), local playlist persistence, and background media playback.

## Tech Stack

- Kotlin + Jetpack Compose
- Media3 (`ExoPlayer`, `MediaSession`, `MediaLibraryService`)
- Hilt (DI)
- Room (local storage)
- OkHttp
- Coroutines + Flow
- Optional Rust FFI shared library (`any_player_ffi_android`)

## Project Layout

- `app/` — Android application module
- `docs/` — acceptance matrix, limitations, blockers, migration notes

## Prerequisites

- Linux/macOS/Windows with:
  - Android Studio (or command-line Android SDK tools)
  - Android SDK (API 35)
  - JDK 21
- For Rust FFI builds (optional but enabled by default):
  - Rust toolchain + Cargo
  - Android NDK installed via SDK Manager
  - Shared Rust workspace at `../any-player-shared-rust`

## Local Configuration

Create/update `local.properties` in the repo root:

- `sdk.dir=/path/to/Android/Sdk` (required)
- `ndk.dir=/path/to/Android/Sdk/ndk/<version>` (recommended for deterministic Rust cross-compile)
- `spotifyClientId=<your_spotify_client_id>` (optional at build time; needed for Spotify auth flow)

You can also pass `spotifyClientId` as a Gradle property:

- `-PspotifyClientId=...`

## Build

### Option A: Android-only build (skip Rust FFI)

Use this if you do not have the sibling Rust workspace available.

```bash
./gradlew :app:assembleDebug -PskipRustFfiBuild=true
```

### Option B: Full build with Rust FFI

This is the default path and expects `../any-player-shared-rust`.

```bash
./gradlew :app:assembleDebug
```

## Install / Run

```bash
./gradlew :app:installDebug -PskipRustFfiBuild=true
```

Or run from Android Studio using the `app` configuration.

## Testing

Unit tests:

```bash
./gradlew :app:testDebugUnitTest -PskipRustFfiBuild=true
```

Instrumented tests (device/emulator required):

```bash
./gradlew :app:connectedDebugAndroidTest -PskipRustFfiBuild=true
```

## Android Auto / Media Service Notes

- Media browsing is exposed via `AnyPlayerMediaLibraryService` in the app manifest.
- Service access control is enforced at runtime in `AnyPlayerMediaLibraryService` via trusted-controller validation (`onConnect` / `onGetSession`).
- Trusted controllers include the app itself, system UID/system apps, and package allowlist entries in `TRUSTED_CONTROLLER_PACKAGES`.
- If a specific head unit host cannot connect, add its package name to `TRUSTED_CONTROLLER_PACKAGES` in `app/src/main/java/com/anyplayer/android/playback/AnyPlayerMediaLibraryService.kt`.
- OAuth deep-link callback for Spotify uses: `anyplayer://spotify-callback`.

## Useful Docs

- `docs/android-acceptance-matrix.md`
- `docs/android-known-limitations.md`
- `docs/android-release-blockers.md`

## Troubleshooting

- **Missing SDK/NDK errors**: verify `sdk.dir` and `ndk.dir` in `local.properties`.
- **Rust build failures**: confirm `../any-player-shared-rust` exists, Cargo is installed, and NDK toolchains are available.
- **Spotify login not working**: ensure `spotifyClientId` is set and redirect URI config matches `anyplayer://spotify-callback`.
- **Android Auto discovery issues**: confirm the app is installed with the latest manifest/service changes and test via DHU or compatible head unit.
