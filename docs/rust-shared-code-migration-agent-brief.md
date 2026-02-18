# Any Player Shared Rust Migration Brief (for AI Coding Agent)

## Goal
Create a **single shared Rust codebase** that is used by:
1. Desktop app at `/home/nesbitt/Desktop/any-player` (Tauri)
2. Android app at `/home/nesbitt/Desktop/any-player-android`

while keeping platform-specific layers thin and separate.

---

## Important current-state findings (already analyzed)

### Desktop Rust project (`/home/nesbitt/Desktop/any-player/src-tauri/src`)
- Tauri-coupled modules:
  - `lib.rs`, `websocket.rs`, `commands/*` (`tauri::command`, app state, event emitters)
  - These are **not shareable** with Android as-is.
- Domain modules with share potential:
  - `models/mod.rs` ✅ (pure serde models, portable)
  - `providers/mod.rs` ✅ (traits + registry mostly portable)
  - `providers/spotify.rs` ⚠️ (portable core logic, but auth callback assumptions are desktop-centric)
  - `providers/jellyfin.rs`, `providers/plex.rs` ⚠️ (likely portable; validate no desktop-only APIs)
- Not ideal to share directly without adapter split:
  - `playback/mod.rs` ❌ currently tightly coupled to `rodio` output and desktop-oriented runtime behavior
  - `config/mod.rs` ❌ uses `keyring` with `linux-native` feature and desktop directories; not Android-compatible as-is
  - `database/mod.rs` ⚠️ portable Rust technically, but Android app already uses Room schema/repository layer

### Android project (`/home/nesbitt/Desktop/any-player-android`)
- No Rust integration currently (no JNI/NDK wiring in `app/build.gradle.kts` yet).
- Android playback/auth/provider stack is Kotlin-based:
  - `core/network/SpotifyClient.kt`
  - `feature/playback/*`
  - Room storage under `core/storage/*`
- Domain model overlap exists and is compatible with Rust model sharing:
  - Rust `models::Track/Playlist/Source`
  - Kotlin `core/model/Track.kt`, `Playlist.kt`, `SourceType.kt`

### Critical constraint learned from recent debugging
- Spotify OAuth client ID/redirect registration and librespot keymaster behavior conflict is real.
- Keep auth orchestration platform-owned; do not hard-wire desktop callback assumptions into shared core.

---

## Recommended architecture

## 1) Create a new shared Rust repository
Create sibling repo:
- `/home/nesbitt/Desktop/any-player-shared-rust`

Use Cargo workspace with these crates:

- `crates/any_player_core` (shared domain + provider business logic)
  - `models`
  - provider trait + registry interfaces
  - provider clients with injected HTTP/auth adapters
  - serialization DTOs for FFI bridge

- `crates/any_player_spotify_engine` (shared Spotify playback/session domain)
  - Spotify session state machine
  - token/session lifecycle logic
  - **No direct desktop audio output API**
  - define trait for audio sink/backend

- `crates/any_player_ffi_android` (JNI wrapper for Android)
  - C ABI/JNI exports around `any_player_core` and `any_player_spotify_engine`
  - JSON/string or byte-buffer boundary for simplicity

- `crates/any_player_desktop_adapter` (optional; or keep in `any-player/src-tauri`)
  - adapter from shared core into Tauri commands/events

---

## 2) What to split first (safe, high-value)

### Phase A (safe to share now)
1. `models/mod.rs`
2. Provider trait contracts from `providers/mod.rs` (without Tauri/state glue)
3. Spotify/Jellyfin/Plex HTTP DTO parsing + request logic as pure Rust services

### Phase B (share with refactor)
1. Spotify session logic from `playback/spotify_session.rs`
   - Keep session orchestration
   - remove desktop-specific assumptions and constants from provider layer
2. Token refresh / auth helper logic

### Phase C (keep platform-specific for now)
1. `playback/mod.rs` rodio sink and playback thread behavior
2. Tauri commands/websocket/event emission
3. Android Media3/Room integration

---

## 3) Integration model per app

### Desktop (`any-player`)
- Keep Tauri shell in `src-tauri`.
- Replace direct imports of old internal modules with shared crates:
  - `any_player_core`
  - `any_player_spotify_engine`
- Keep Tauri command signatures unchanged where possible.

### Android (`any-player-android`)
- Add `rust/` module or `native/` directory referencing `any_player_ffi_android`.
- Build `.so` for `arm64-v8a` first; add more ABIs later.
- JNI bridge called from Kotlin service/repository layer.
- Start by replacing **only Spotify auth + session checks**, not full playback queue all at once.

---

## 4) Execution steps for AI coding agent

## Step 0 — Restore Android baseline before migration
- Revert temporary Spotify client-ID override test changes and ensure Android uses app-owned client ID configuration again.
- Confirm login works (`INVALID_CLIENT` is gone).

## Step 1 — Bootstrap shared workspace
In `/home/nesbitt/Desktop/any-player-shared-rust`:
- Create `Cargo.toml` workspace.
- Add crates listed above.
- Configure dependencies to be cross-platform-friendly:
  - Prefer `reqwest` with `rustls-tls` where feasible.
  - Avoid desktop-only features in shared crates.

## Step 2 — Move portable domain models/contracts
- Copy and adapt `models/mod.rs` into `any_player_core`.
- Copy provider trait and error types into `any_player_core::providers`.
- Keep serde field names stable to match Android Kotlin model mapping.

## Step 3 — Extract provider client logic
- Move Spotify/Jellyfin/Plex HTTP code into shared service modules.
- Separate these concerns:
  - `AuthFlow` trait (platform-specific)
  - `ProviderApi` (shared)
- Ensure no references to Tauri, keyring, desktop callback server.

## Step 4 — Extract Spotify session engine
- Move session manager logic from `playback/spotify_session.rs` into `any_player_spotify_engine`.
- Replace hard-coded client IDs with injected configuration.
- Expose pure methods:
  - `initialize_with_token(...)`
  - `refresh_if_needed(...)`
  - `is_ready()`
  - `close()`

## Step 5 — Desktop adaptation
In `/home/nesbitt/Desktop/any-player/src-tauri`:
- Point Cargo dependencies to shared workspace (path dependency first).
- Keep `commands/*` and `lib.rs` as wrappers that call shared crates.
- Keep websocket/event code untouched.

## Step 6 — Android FFI adapter
In shared repo crate `any_player_ffi_android`:
- Expose minimal JNI API first:
  - `init(config_json)`
  - `spotify_begin_auth(config_json)` (or pass-through helper)
  - `spotify_exchange_code(code, verifier, redirect)`
  - `spotify_validate_token(token)`
  - `spotify_init_session(token)`
  - `spotify_session_ready()`
- Return structured JSON errors.

In Android app:
- Add JNI loader (`System.loadLibrary`).
- Add Kotlin wrapper class (`RustBridge`) to call native methods.
- Replace only one narrow flow first (token validate/session init), keep Kotlin fallback paths.

## Step 7 — CI + build validation
- Desktop:
  - `cargo check` in shared workspace
  - Tauri app build still passes
- Android:
  - Build native lib for target ABI
  - `./gradlew :app:assembleDebug` passes
  - smoke test: login + session init path

---

## 5) Concrete compatibility rules (must follow)

1. **No Tauri types in shared crates** (`tauri::State`, commands, AppHandle).
2. **No desktop keyring usage in shared crates**.
3. **No rodio/cpal in shared core** (audio backend must be trait-based and optional).
4. **No localhost callback assumptions** in shared auth logic.
5. **No direct Room/SQLite schema coupling** in shared crates initially.
6. **All shared crate public APIs must be runtime-agnostic** (Tokio okay, UI-framework agnostic).

---

## 6) Suggested target directory layout after migration

- `/home/nesbitt/Desktop/any-player-shared-rust`
  - `Cargo.toml` (workspace)
  - `crates/any_player_core`
  - `crates/any_player_spotify_engine`
  - `crates/any_player_ffi_android`

- `/home/nesbitt/Desktop/any-player/src-tauri`
  - depends on shared crates by path/git
  - retains Tauri shell and command adapter

- `/home/nesbitt/Desktop/any-player-android`
  - JNI bridge Kotlin layer
  - native lib consumption from shared workspace outputs

---

## 7) Rollout strategy

1. Share models + provider API contracts first.
2. Move Spotify auth/session logic second.
3. Keep playback output implementations platform-specific until stable.
4. Only then consider deeper playback unification.

This minimizes risk and avoids breaking both apps simultaneously.

---

## 8) Acceptance criteria

- Shared crates compile independently on Linux host.
- Desktop app compiles and can still:
  - authenticate provider(s)
  - list playlists
  - start playback via existing shell
- Android app compiles and can:
  - call JNI shared methods
  - validate Spotify token
  - initialize session readiness path
- No Tauri imports inside shared crates.
- No desktop keyring or rodio hard dependency inside shared core crates.

---

## 9) Known risks and mitigations

- **Spotify policy/internal endpoint behavior changes**
  - Mitigation: keep provider auth orchestrated by platform shell; isolate Spotify-specific logic.
- **JNI complexity and ABI build setup**
  - Mitigation: start with narrow JNI API and one ABI (`arm64-v8a`) first.
- **Desktop regressions during extraction**
  - Mitigation: adapter layer preserves old command surface.

---

## 10) Immediate next action for coding agent

Start with Step 0 and Step 1 only, open a PR that:
1. Restores Android client-ID baseline
2. Creates shared Rust workspace skeleton with empty crates and CI `cargo check`

Then continue in incremental PRs by phase.
