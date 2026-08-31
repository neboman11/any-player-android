# PlaybackQueueManager Decomposition Plan

Last updated: 2026-08-31

## Why staged, not a single rewrite

`PlaybackQueueManager.kt` (~1660 lines) dispatches every operation (`play`,
`pause`, `togglePlayPause`, `seekTo`, `setVolume`, `setShuffle`, `next`,
`previous`, `playFromIndex`, `syncFromPlaybackEngine`, ...) through
`if (mixedMode) {...} else if (spotifyMode) {...} else {...}` branches, ~24
sites across 16 methods. All three branches read/write the same large
mutable surface: `mutableStatus`, `scope`, `queueIndexCache`, `recovery`
(`PlaybackRecoveryState`), `spotifyQueueRequiresReload`,
`playableQueueIndices`, `addToQueueInsertionOffset`, `lastPrefetchedForTrackId`.

Splitting this into separate-file classes means every one of those lines
needs a qualifier rewrite, since Kotlin has no way to share private-field
access across files without going through a constructor-injected reference.
A mechanical rename at this scale already produced one real regression in
this codebase (`$recovery.spotifyRecoveryAttempts` silently breaking log
interpolation after the `PlaybackRecoveryState` extraction — fixed in
`8e64b3a`) — and that was the *safe*, compiler-checked kind of mistake
(reference typo). A control-flow logic mistake in this state machine would
not necessarily be caught by the compiler or by the existing (thin, per
prior review) unit test suite, and this is a timing-sensitive Spotify
Connect / Media3 auto-advance and error-recovery engine that has already
needed 3 rounds of post-hoc regression fixes in this same PR (audio focus,
command mutex, full-context playback, window uids).

So: one mode at a time, each its own commit, each verified by build + full
test suite + **manual on-device check** before starting the next mode.
Do not proceed to the next stage until the current stage is confirmed
working on a real device.

## Target architecture

```
PlaybackQueueManager (thin orchestrator)
  ├─ owns: PlaybackEngineContext (shared mutable state, see Stage 0)
  ├─ owns: LocalPlaybackOps   (Media3/Jellyfin/local-file mode)
  ├─ owns: SpotifyPlaybackOps (Spotify Connect mode)
  ├─ owns: MixedPlaybackOps   (mixed queue; delegates per-track to the two above)
  ├─ keeps: setQueue/addNextInQueue (mode computation is shared, stays here)
  ├─ keeps: restorePersistedState/persistState/persistStateAsync (mode-agnostic)
  └─ each public method becomes a 1-3 line dispatch to the active Ops class
```

`PlaybackEngineContext` is a shared, mutable, `internal` state holder (same
pattern as the existing `PlaybackRecoveryState` extraction) — not an
attempt to eliminate shared state, which this engine genuinely needs.
The goal is moving *behavior* into per-mode files, not eliminating state
sharing, which would be a much larger redesign than "break up the file."

## Stage 0 — Safety net before moving anything (do this first, every time)

**Status: done, committed.** 29 characterization tests added in
`PlaybackQueueManagerTest.kt` (local/Spotify/mixed dispatch for
`play`/`pause`/`togglePlayPause`/`next`/`previous`/`seekTo`/`setVolume`/
`setShuffle`/`setRepeatMode`/`playFromIndex`); `syncFromPlaybackEngine` is
private and driven only by the internal poll loop, so it isn't covered by
a public-API test yet — flagged as a gap for whoever picks up Stage 1-3.
All 12 dispatch methods (`play`, `pause`, `togglePlayPause`, `seekTo`,
`setVolume`, `setShuffle`, `setRepeatMode`, `next`, `previous`,
`playFromIndex`, `syncFromPlaybackEngine`) now do a 3-line (or 2-line,
where a mode is a no-op) dispatch to same-class `*Local()`/`*Spotify()`/
`*Mixed()` private methods. Full build + full test suite green after each
step. Ready for Stage 1.

1. Read the current `syncFromPlaybackEngine`, `next()`, `previous()`,
   `play()`, `pause()`, `togglePlayPause()`, `seekTo()`, `setVolume()`,
   `setShuffle()`, `setRepeatMode()`, `playFromIndex()`, `triggerPrefetch()`
   bodies precisely (use `cat -n` / a scratch copy — the Read tool has been
   observed to mangle this specific file's content on direct reads; copy to
   scratch first and re-read from there).
2. Write characterization unit tests for each mode's current behavior in
   `PlaybackQueueManagerTest.kt` for any operation not already covered —
   the prior review flagged this state machine's test coverage as thin.
   These tests must pass against the **current, un-refactored** code before
   any extraction starts; they're the regression net for every later stage.
3. Within `PlaybackQueueManager.kt` only (no new files yet), Extract Method
   each mode's branch body into a clearly named private method
   (`playLocal()`, `playSpotify()`, `playMixed()`, `syncLocalMode()`,
   `syncSpotifyMode()`, `syncMixedMode()`, etc.), so each public method
   becomes a 3-way dispatch to same-class private methods. This is pure
   Extract Method — same class, same field access, zero reference changes,
   behavior-identical by construction. Build + full test suite after.
4. Commit Stage 0 alone. This step alone already answers most of the
   original review complaint (methods are no longer "one large state
   machine spread across every operation" — they're now a 3-line dispatch
   to a well-named method), so it's worth doing even if Stages 1-3 stall.

## Stage 1 — Extract local/Media3 mode (lowest risk, no Spotify Connect races)

**Status: code committed, manual on-device check still pending** (user
will verify lockscreen/Bluetooth/Android Auto/notification/media session
behavior later). `PlaybackEngineContext.kt` (50 lines) holds the shared
mutable state; `LocalPlaybackOps.kt` (179 lines) holds all 11 `*Local()`
methods from Stage 0, moved in verbatim and qualified with `context.`;
`PlaybackQueueManager.kt` shrank 1767 → 1603 lines. Full build + full test
suite green. If the device check turns up a regression, revert this
stage's commit rather than patching forward.

1. Create `PlaybackEngineContext.kt`: holds `mutableStatus`, `scope`,
   `queueIndexCache`, `recovery`, `playableQueueIndices`,
   `spotifyQueueRequiresReload`, `addToQueueInsertionOffset`,
   `lastPrefetchedForTrackId` as `var`/`val` properties (moved verbatim from
   `PlaybackQueueManager`, mechanical). `PlaybackQueueManager` holds
   `private val context = PlaybackEngineContext(...)`.
2. Create `LocalPlaybackOps.kt`: constructor takes
   `media3PlaybackController`, `audioCacheManager`, `context`. Move the
   already-extracted `*Local()`/local-branch methods from Stage 0 in
   verbatim, qualifying field access with `context.`.
3. `PlaybackQueueManager`'s dispatch methods call `localOps.play()` etc. for
   the local branch; mixed/Spotify branches stay inline in
   `PlaybackQueueManager` for this stage (not yet extracted).
4. Build + full test suite. **Manual device check**: play/pause/seek/skip/
   shuffle/repeat through a local-only or Jellyfin-only queue, confirm
   lockscreen/Bluetooth/Android Auto controls stay in sync, confirm
   notification and media session behave normally.
5. Commit Stage 1 only after the manual check passes.

## Stage 2 — Extract Spotify mode

**Status: code committed, manual on-device check still pending** (user
will verify Spotify-only playback, Connect handoff, auto-advance, and
recovery-after-restart later). `SpotifyPlaybackOps.kt` (576 lines) holds
all Spotify-only dispatch methods plus `maybeRecoverSpotifyTrack`,
`awaitSpotifyAdvance`, `fallbackAdvanceSpotifyQueue`,
`startSpotifyAtQueueIndex`, `spotifyErrorOrDefault`,
`spotifyPlaybackQueue`, `spotifyPlaybackTrackIds`,
`currentSpotifyQueueIndex`. `maybeRecoverSpotifyTrack` and
`spotifyErrorOrDefault` stayed public since mixed-mode code still inline
in `PlaybackQueueManager` (not extracted until Stage 3) calls them too;
the rest are private to `SpotifyPlaybackOps` since nothing outside it
calls them. `PlaybackQueueManager.kt` shrank 1603 → 1060 lines. Full
build + full test suite green.

If the device check turns up a regression, revert this stage's commit
rather than patching forward.

1. Create `SpotifyPlaybackOps.kt`: constructor takes
   `spotifyPlaybackController`, `context`. Move the Stage-0-extracted
   `*Spotify()` methods in verbatim, qualifying with `context.`. This
   includes `maybeRecoverSpotifyTrack`, `awaitSpotifyAdvance`,
   `fallbackAdvanceSpotifyQueue`, `startSpotifyAtQueueIndex`,
   `spotifyErrorOrDefault`, `spotifyPlaybackQueue`,
   `spotifyPlaybackTrackIds`, `currentSpotifyQueueIndex` — these are
   Spotify-only already, low ambiguity about ownership.
2. Wire `PlaybackQueueManager`'s dispatch methods to call
   `spotifyOps.play()` etc. for the Spotify branch.
3. Build + full test suite. **Manual device check**: Spotify-only queue —
   play/pause/seek/skip/shuffle/repeat, Spotify Connect handoff to/from
   another device, auto-advance at end of track, recovery after killing/
   restarting the Spotify app mid-playback, Android Auto queue.
4. Commit Stage 2 only after the manual check passes.

## Stage 3 — Extract mixed mode (highest risk, do last, do carefully)

**Status: code committed, manual on-device check still pending** (user
will verify a queue mixing local/Jellyfin and Spotify tracks: source
transitions in both directions, end-of-track auto-advance across both
boundary directions, stall detection/recovery). `MixedPlaybackOps.kt`
(365 lines) holds all mixed-mode dispatch methods plus
`mixedPlaybackSequence`, `spotifyFallbackQueue`, `playMixedTrackAtIndex`,
`playMixedTrackById`, `triggerPrefetch`. `playMixedTrackAtIndex` and
`triggerPrefetch` stayed public since `PlaybackQueueManager.setQueue`
(mode-agnostic, stays there) calls them too; `PlaybackQueueManager` keeps
a thin `private fun triggerPrefetch() = mixedOps.triggerPrefetch()`
re-export since `LocalPlaybackOps` was already wired to a
`::triggerPrefetch` callback in Stage 1, before `mixedOps` existed.
`PlaybackQueueManager.kt` shrank 1060 → 741 lines (down from 1666
originally) and now already matches the Stage 4 target shape below. Full
build + full test suite green.

If the device check turns up a regression, revert this stage's commit
rather than patching forward.

1. Create `MixedPlaybackOps.kt`: constructor takes `localOps`, `spotifyOps`,
   `context`, plus whatever mixed-only helpers remain
   (`mixedPlaybackSequence`, `spotifyFallbackQueue`, `playMixedTrackAtIndex`,
   `playMixedTrackById`, `triggerPrefetch`). Move the Stage-0-extracted
   `*Mixed()` methods in verbatim, qualifying with `context.` and
   delegating single-track dispatch to `localOps`/`spotifyOps` where the
   current code already does per-track source checks.
2. Wire remaining dispatch sites.
3. Build + full test suite. **Manual device check**: a queue mixing local/
   Jellyfin and Spotify tracks — play through several source transitions
   in both directions (next/previous), confirm end-of-track auto-advance
   across the Local→Spotify and Spotify→Local boundary, confirm stall
   detection/recovery still fires (was fixed 3x already in this PR — this
   is the highest-regression-risk path).
4. Commit Stage 3 only after the manual check passes.

## Stage 4 — Cleanup

1. `PlaybackQueueManager.kt` should now be close to: constructor, `status`/
   `audioNormalizationSettings` flows, `setQueue`/`addNextInQueue` (mode
   computation), `restorePersistedState`/`persistState`/`persistStateAsync`,
   the init polling loop, and thin dispatch methods.
2. Re-run full test suite + lint. Diff line counts before/after per file for
   the final report.
3. Squash/tidy commit history for this decomposition into one clean PR
   description covering all 4 stages, or leave as 4 separate reviewable
   commits — user's call at that point.

## Rollback

Each stage is its own commit. If a manual device check fails at any stage,
revert that stage's commit and stop — do not attempt to patch forward into
the next stage on top of an unverified one.

## Explicitly out of scope for this plan

- Removing shared mutable state entirely (event-sourced/actor-based
  redesign) — a different, larger project than "break up the file."
- Changing any public API of `PlaybackQueueManager` consumed by
  `MainViewModel`, `AnyPlayerMediaLibraryService`, `MediaSessionPlayerBridge`,
  `CustomPlaylistEngine`, etc. — this is a pure internal restructuring.
