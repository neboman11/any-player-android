# Android Acceptance Matrix (Phase 7)

This checklist maps current implementation progress to the spec acceptance matrix.

## 10.1 Auth

- [x] Connect/disconnect/status for Jellyfin, Plex, Spotify metadata flow.
- [x] Session restore integrated in startup orchestration.
- [~] Spotify full OAuth code exchange/token refresh still scaffold-level (status + auth URL path implemented).

## 10.2 Playback

- [x] Play/pause/toggle, next/previous, seek, volume, shuffle, repeat.
- [x] Queue jump semantics (`play from selected row`) implemented and fixed in Now Playing queue list.
- [x] Media3-backed playback path for provider stream URLs.
- [~] Full notification and transport-control polish may still need production hardening pass.

## 10.3 Search

- [x] Source filters (`all/spotify/jellyfin/plex`) and type filters (`tracks/playlists`).
- [x] Provider-backed track and playlist search for Jellyfin/Plex.
- [x] Play from search rows.
- [x] Add-to-custom-playlist from search rows.

## 10.4 Playlists

- [x] Standard custom playlist create/delete/select/play.
- [x] Add/remove tracks and play-from-track for standard playlists.
- [x] Union playlist create + add provider/custom sources + materialization + play.
- [~] Rich reorder UI gestures are not yet exposed; reorder engine API exists.

## 10.5 State Transfer

- [x] Portable export/import.
- [x] Private encrypted export/import (AES-GCM + PBKDF2).
- [x] Merge policies and deterministic remap behavior implemented.
- [x] Dry-run import and summary messaging.

## 10.6 Persistence / Startup Resilience

- [x] Playback status persistence hooks and queue state management.
- [x] Provider playlist cache stored and used for fallback startup.
- [x] Startup progress + timeout/retry + continue-without-provider controls.

## Manual verification remaining

- Verify end-to-end Android ↔ desktop state round-trip with real files.
- Verify provider-specific edge payloads across diverse Jellyfin/Plex server versions.
- Verify notifications/background controls behavior on target Android versions/devices.
