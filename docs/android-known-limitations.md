# Android Known Limitations

## Spotify direct playback
- Spotify playback on Android can be constrained by API/device policy and account capabilities.
- Current implementation persists Spotify connection metadata and surfaces fallback status when direct playback readiness is unavailable.
- UI intentionally exposes this state and does not silently claim active playback support.

## Provider API variance
- Jellyfin and Plex API payloads vary by server version and plugin stack.
- Network clients are scaffolded and isolated behind repository interfaces for incremental hardening.

## File picker integration
- State import/export currently supports path-based file IO in-app.
- System document picker UX wiring should be added to align with production-grade SAF flows.
