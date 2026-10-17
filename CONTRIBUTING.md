# Contributing to Any Player Android

Read [README.md](README.md) for setup and [ARCHITECTURE.md](docs/ARCHITECTURE.md)
for the built system and compatibility contracts. Keep each branch/PR focused on one
purpose; include validation evidence and screenshots/video for UI changes.

## Setup and checks

Use JDK 21 and Android SDK API 35/build tools 35.0.0. Rust and the Android NDK are not
required. Set `sdk.dir` and, for Spotify sign-in, `spotifyClientId` in gitignored
`local.properties` as described in README.

```bash
./gradlew :app:assembleDebug
./gradlew :core:model:test :core:data:testDebugUnitTest :playback:testDebugUnitTest :dj:testDebugUnitTest :app:testDebugUnitTest
./gradlew --console=plain -q :app:lintDebug
```

Install/run with a deliberately selected device (`ANDROID_SERIAL` for Gradle,
`adb -s <serial>` for direct adb commands):

```bash
./gradlew :app:installDebug
```

Room migration/repository instrumentation requires that selected device/emulator:

```bash
./gradlew :core:data:connectedDebugAndroidTest
```

CI runs all module unit tests, debug assembly, APK upload and app lint. Do not suppress
or disable lint to hide a real issue; report the finding and fix it in its owning layer.
Validate Android Auto/session/notification changes with DHU or compatible hardware.

## Module boundaries and contracts

- `:core:model` (`com.anyplayer.android.core.model`): pure Kotlin/JVM domain/wire
  models and reducers. No Android imports or I/O.
- `:core:data` (`com.anyplayer.android.data`): repository, auth, settings, transfer,
  sync and startup **contracts**; Room/store/provider implementations. `SyncEngine`
  and `AppStartup` implementations live in `:app`, not `:core:data`.
- `:playback` (`com.anyplayer.android.playback`): `PlaybackEngine` and
  `backend.PlaybackBackend` contracts, engine/backends, persistence and session.
  The service alone retains the legacy `com.anyplayer.android.feature.playback.service`
  package for Android clients.
- `:dj` (`com.anyplayer.android.dj`): `DjController`, coordinator, generators and
  artifact/passages lifecycle; depends on model, data and playback.
- `:app` (`com.anyplayer.android`): Compose UI, startup/sync orchestration and Hilt
  cross-module wiring. `AppModule` supplies named `spotifyClientId`, `appVersionCode`
  and `appVersionName` values from BuildConfig.

Dependency direction: app → dj/playback/data/model; dj → playback/data/model;
playback → data/model; data → model. Reuse contracts in `core/data` and `playback`
instead of adding UI-specific duplicates. UI must not call DAOs, OkHttp or ExoPlayer.
Keep mutable flows private; expose `StateFlow`/`SharedFlow` or repository `Flow`.

## UI and state conventions

- One ViewModel per screen/responsibility, not a shared application-sized UI state.
  Settings tabs each have their own ViewModel; `ShellViewModel` owns shell concerns.
- Route composables obtain ViewModels and collect state with
  `collectAsStateWithLifecycle`; screen components receive state and actions.
- Keep UI models immutable. Use queue-entry `uid` keys (duplicate track ids are valid)
  and original occurrence/index keys for track tables. Build large derived lists off-main.
- Cancel stale searches with `collectLatest`; keep loading/empty/error states explicit.
- Commit slider changes on `onValueChangeFinished`. Position tickers belong only in
  visible playing position widgets; do not poll/rebuild whole-screen state.
- Sync target/token edits commit on IME done/focus loss and when leaving Settings or
  switching tabs. Persisting drafts does not reconnect WebSocket; Connect does.

## Threading and playback rules

- Engine intents, ExoPlayer and session player are Main-confined. Public engine
  commands may be called from any thread; the engine dispatches to `Main.immediate`.
- Keep disk, preferences/Keystore, large JSON, file transfer and database open/query work
  off Main (`Dispatchers.IO`; Room suspend/Flow APIs). Never add `allowMainThreadQueries`.
- HTTP uses asynchronous OkHttp calls with suspend cancellation; backoff uses `delay`,
  never `Thread.sleep`. Preserve response cleanup and cancellation propagation.
- Dedupe, track-table/queue UI transformations use `Dispatchers.Default`/the injected
  compute dispatcher. Spotify serializes work on `Default.limitedParallelism(1)` with
  a command mutex; DJ scheduling uses its own single-thread dispatcher.
- LLM/TTS/DSP use the dedicated low-priority native DJ thread, including native release;
  artifact/file I/O switches to IO. Do not move inference onto Main or Default workers.
- Playback uses a level-triggered reconcile loop: never cancel an in-flight backend
  command. Preserve loadId/item-identity stale-event checks and USER/SYNC/RESTORE origins.
- Do not replace event-driven sync with periodic position pushes. Restored queues stay
  paused; startup must not start audible Spotify playback.

## Persistence and security

- Route mutations through repositories and transactional DAOs; preserve Room schema
  exports and migration tests. Never add destructive migration fallback.
- Keep the `any_player.db` name, encrypted preference files/keys, serialization names,
  import/export crypto and legacy service/deep-link contracts compatible.
- Queue content and cursor belong in Room (`queue_entries`/`queue_meta`); non-secret
  flags/position belong in `player_settings`. Do not re-encrypt the whole queue per tick.
- Keep tokens out of logs, commits and screenshots. Do not commit `local.properties`,
  downloaded models/voices or generated AAR/APKs. Preserve secure-store handling.
- Validate imported payloads, keep dry runs write-free and test duplicate occurrences,
  merge remaps and retained local playlist children.

## Pull request checklist

- [ ] Scope, naming and module boundaries match nearby code.
- [ ] Relevant regression tests added/updated; all five module unit-test tasks pass.
- [ ] `:app:assembleDebug` and `:app:lintDebug` pass (or real findings are explicitly reported).
- [ ] Migration/repository instrumentation run for persistence changes.
- [ ] Affected playback, notification, restore and Android Auto flows manually checked.
- [ ] README and architecture updated for behavior/developer workflow changes.
- [ ] PR describes commands/results, any limitations and compatibility implications.
