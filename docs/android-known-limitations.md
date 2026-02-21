# Android Known Limitations

## Spotify direct playback
- Spotify playback on Android can be constrained by API/device policy and account capabilities.
- Current implementation persists Spotify connection metadata and surfaces fallback status when direct playback readiness is unavailable.
- UI intentionally exposes this state and does not silently claim active playback support.

## Spotify OAuth lifecycle
- Spotify status/auth URL flow is implemented, but full production-hard OAuth exchange + refresh validation still requires completion and device-level testing.

## Provider API variance
- Jellyfin and Plex API payloads vary by server version and plugin stack.
- Network clients are scaffolded and isolated behind repository interfaces for incremental hardening.

## File picker integration
- State import/export currently supports path-based file IO in-app.
- System document picker UX wiring should be added to align with production-grade SAF flows.

## Rust FFI workspace dependency
- Full Rust-backed builds require sibling workspace `../any-player-shared-rust`.
- Android-only Kotlin iteration is supported via `-PskipRustFfiBuild=true` for local development.
