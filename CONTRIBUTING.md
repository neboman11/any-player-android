# Contributing to Any Player Android

Thanks for helping improve Any Player Android. This guide covers the current development workflow, quality checks, and contribution expectations for this repository.

## Before you start

1. Read [README.md](README.md) for setup and app overview.
2. Review open docs in `docs/` for current constraints and release context.
3. Make sure Android prerequisites are installed:
   - Android SDK (API 35)
   - JDK (21+)

## Development setup

Generate/update local config in `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
spotifyClientId=<your_spotify_client_id>
```

Build the app:

```bash
./gradlew :app:assembleDebug
```

Install debug build on a connected device/emulator:

```bash
./gradlew :app:installDebug
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
./gradlew :app:assembleDebug
./gradlew :core:model:test
./gradlew :app:lintDebug
```

Notes:
- Instrumented tests require a running emulator/device:

```bash
./gradlew :core:data:connectedDebugAndroidTest
```

- Android Auto/media browser behavior should be manually validated using DHU or compatible hardware where relevant.

## Project architecture (current)

The v2 rewrite is a multi-module build; see `docs/ARCHITECTURE.md` for the design.

- `:core:model` — pure Kotlin domain models and logic (queue ops, run planning, dedupe, normalization).
- `:core:data` — Room database and migrations, secure stores.
- `:playback` — playback engine, ExoPlayer backend, `AnyPlayerMediaLibraryService` + session player.
- `:dj` — AI DJ.
- `:app` — application, activity and UI.

## Coding guidelines

### Kotlin / Android

- Use `camelCase` for functions/variables and `PascalCase` for classes/types.
- Respect module boundaries (`docs/ARCHITECTURE.md` §3); prefer focused, composable classes.
- Use coroutines/Flow patterns consistently with nearby code.
- Keep DI wiring in Hilt modules and avoid ad hoc service locators.
- Route persistent data changes through Room DAOs/repositories and preserve schema compatibility.

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
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) when the design changes.

## Pull request checklist

- [ ] Changes are scoped and follow existing architecture.
- [ ] `./gradlew :app:assembleDebug` passes.
- [ ] `./gradlew :core:model:test` passes.
- [ ] `./gradlew :app:lintDebug` passes.
- [ ] Manual verification completed for affected flows (including Android Auto/playback when applicable).
- [ ] Docs updated if behavior/workflow changed.

## Questions

- Open an issue for discussion.
- Reference existing patterns in the codebase and docs.
- Ask maintainers in the PR thread when design tradeoffs are unclear.

Thanks again for contributing.
