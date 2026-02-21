# Contributing to Any Player Android

Thanks for helping improve Any Player Android. This guide covers the current development workflow, quality checks, and contribution expectations for this repository.

## Before you start

1. Read [README.md](README.md) for setup and app overview.
2. Review open docs in `docs/` for current constraints and release context.
3. Make sure Android prerequisites are installed:
   - Android SDK (API 35)
   - JDK (17+)
   - Android NDK (required for full Rust FFI build path)
4. If you plan to build Rust FFI, ensure the shared workspace exists at `../any-player-shared-rust`.

## Development setup

Generate/update local config in `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
ndk.dir=/path/to/Android/Sdk/ndk/<version>
spotifyClientId=<your_spotify_client_id>
```

Build the app:

```bash
./gradlew :app:assembleDebug
```

Android-only mode (skip Rust FFI build):

```bash
./gradlew :app:assembleDebug -PskipRustFfiBuild=true
```

Install debug build on a connected device/emulator:

```bash
./gradlew :app:installDebug -PskipRustFfiBuild=true
```

## Recommended contributor workflow

1. Create a feature branch.
2. Keep changes scoped to one purpose per PR.
3. Run the checks below before opening a PR.
4. Update docs when user-visible behavior or developer workflow changes.
5. Open a PR with a clear description, validation steps, and screenshots/video for UI changes.

## Quality checks

Run these before submitting:

```bash
./gradlew :app:assembleDebug -PskipRustFfiBuild=true
./gradlew :app:testDebugUnitTest -PskipRustFfiBuild=true
./gradlew :app:lintDebug -PskipRustFfiBuild=true
```

If you are validating full FFI integration, run the same commands without `-PskipRustFfiBuild=true` and with a configured NDK/shared Rust workspace.

Notes:
- Instrumented tests require a running emulator/device:

```bash
./gradlew :app:connectedDebugAndroidTest -PskipRustFfiBuild=true
```

- Android Auto/media browser behavior should be manually validated using DHU or compatible hardware where relevant.

## Project architecture (current)

- Android app (`app/src/main/java/com/anyplayer/android`): app entry, playback services, and UI integration.
- Core (`app/src/main/java/com/anyplayer/android/core`): network clients, storage (Room), models, DI, and Rust bridge.
- Features (`app/src/main/java/com/anyplayer/android/feature`): auth, playback, playlists, providers, startup, and state transfer.
- Media service: `AnyPlayerMediaLibraryService` provides media browsing/session integration.
- Docs (`docs/`): known limitations and release blockers.

## Coding guidelines

### Kotlin / Android

- Use `camelCase` for functions/variables and `PascalCase` for classes/types.
- Keep feature code under existing `feature/*` boundaries; prefer focused, composable classes.
- Use coroutines/Flow patterns consistently with nearby code.
- Keep DI wiring in Hilt modules and avoid ad hoc service locators.
- Route persistent data changes through Room DAOs/repositories and preserve schema compatibility.

### Rust FFI (when touched)

- Keep JNI/FFI boundary contracts explicit and backward compatible.
- Preserve ABI output naming/locations expected by Gradle tasks.
- Validate Android target builds before submitting FFI-related PRs.

### General

- Match existing patterns before introducing new abstractions.
- Avoid unrelated refactors in feature/fix PRs.
- Keep naming and folder placement consistent with nearby code.

## Security & data handling

- Never commit secrets, API keys, or provider tokens.
- Do not log sensitive auth/session values.
- Preserve existing secure storage and crypto handling patterns.
- Validate and sanitize user-provided inputs and imported state payloads.

## Documentation expectations

Update relevant docs when behavior changes:

- [README.md](README.md) for developer setup and workflow updates.
- [docs/android-known-limitations.md](docs/android-known-limitations.md) when constraints change.
- [docs/android-release-blockers.md](docs/android-release-blockers.md) when blocker status/criteria changes.

## Pull request checklist

- [ ] Changes are scoped and follow existing architecture.
- [ ] `./gradlew :app:assembleDebug -PskipRustFfiBuild=true` passes.
- [ ] `./gradlew :app:testDebugUnitTest -PskipRustFfiBuild=true` passes.
- [ ] `./gradlew :app:lintDebug -PskipRustFfiBuild=true` passes.
- [ ] Manual verification completed for affected flows (including Android Auto/playback when applicable).
- [ ] Docs updated if behavior/workflow changed.

## Questions

- Open an issue for discussion.
- Reference existing patterns in the codebase and docs.
- Ask maintainers in the PR thread when design tradeoffs are unclear.

Thanks again for contributing.
