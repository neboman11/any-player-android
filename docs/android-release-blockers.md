# Android Release Blockers and Action Plan

This list turns current acceptance deltas into concrete implementation tasks.

Last updated: 2026-02-21

## P0 — Must complete before release

### 1) Spotify OAuth completion + refresh flow

#### 1.1 Why blocked

- Current Spotify path is metadata/auth-URL scaffold; full auth code exchange/refresh lifecycle is not end-to-end.

#### 1.2 Tasks

- Implement callback handling and auth-code exchange in Android auth layer.
- Persist refresh token securely and refresh access token on expiry.
- Surface real Spotify connection state transitions in UI (connected, expired, refresh-failed).
- Add instrumentation/logic tests for token refresh success/failure paths.

#### 1.3 Exit criteria

- Connect/disconnect/status for Spotify passes manually across app restart.
- Expired token refreshes automatically or shows explicit recoverable failure.

---

### 2) Android ↔ Desktop state round-trip verification

#### 2.1 Why blocked

- Round-trip is implemented but still marked manual verification remaining.

#### 2.2 Tasks

- Export Android portable + private files and import into desktop app.
- Export back from desktop and re-import on Android using all merge modes.
- Verify ID remap/references for custom playlists, union sources, and tracks.
- Validate corrupt/invalid ciphertext and unsupported version behavior.

#### 2.3 Exit criteria

- Round-trip preserves structure/references under merge modes.
- Invalid/corrupt inputs fail safely with clear summary.

---

### 3) Transport control validation sweep

#### 3.1 Why blocked

- Media-style notification and media service discovery were recently updated and need release-level verification coverage.

#### 3.2 Tasks

- Validate lockscreen/Bluetooth/headset/Auto host command handling for play/pause/next/previous.
- Validate background lifecycle transitions (screen off, app background, process reclaim scenarios).
- Capture a reproducible checklist across Android 13/14/15 target devices.

#### 3.3 Exit criteria

- External transport controls remain synchronized with in-app playback state.
- No service/notification desync in validated lifecycle scenarios.

---

## P1 — Should complete for parity quality

### 4) Reorder UX for custom/union playlists

#### 4.1 Why blocked

- Reorder engine APIs exist, but richer reorder UI gestures are not exposed.

#### 4.2 Tasks

- Add UI controls for reordering custom playlist tracks.
- Add UI controls for reordering union playlist source order.
- Persist and rehydrate order after restart.

#### 4.3 Exit criteria

- Reorder actions are user-accessible and persisted.

---

### 5) Provider compatibility sweep (Jellyfin/Plex variants)

#### 5.1 Why blocked

- Needs validation against server-version payload variability.

#### 5.2 Tasks

- Test search/playlist/track mapping against at least two Jellyfin and two Plex server variants.
- Add fallback parsing for missing/variant fields where failures occur.
- Expand mapper tests with sampled payload fixtures.

#### 5.3 Exit criteria

- Core playlist/search flows remain stable across tested server variants.

---

## P2 — Environment/CI closure

### 6) Local toolchain alignment for full test execution

#### 6.1 Why blocked

- Local environment currently only exposes Java 25; Gradle/Kotlin toolchain run is blocked.

#### 6.2 Tasks

- Install/configure JDK 21 locally.
- Re-run `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:lintDebug` locally.
- Confirm parity with CI workflow behavior.

#### 6.3 Exit criteria

- Local and CI build/test/lint all pass using the same major JDK.

---

## Suggested execution order

1. Spotify OAuth completion + tests
2. Round-trip state transfer verification
3. Transport control validation sweep
4. Reorder UX wiring
5. Provider compatibility sweep
6. Local toolchain alignment and full validation pass

