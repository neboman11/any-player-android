package com.anyplayer.android.playback.spotify

import android.os.SystemClock
import com.anyplayer.android.core.model.BackendKind
import com.anyplayer.android.core.model.PlanItem
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.Run
import com.anyplayer.android.core.model.normalizeSpotifyTrackId
import com.anyplayer.android.playback.backend.BackendEvent
import com.anyplayer.android.playback.backend.BackendPreparer
import com.anyplayer.android.playback.backend.ItemKey
import com.anyplayer.android.playback.backend.PlaybackBackend
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plays a SPOTIFY [Run] on a Spotify Connect device through the Web API (design §6.2, §12.3).
 *
 * The run's tracks are sent as one `uris` list so the device advances natively; a poller (only
 * while a run is held) maps the polled track back to run items. All state lives on one
 * single-threaded [scope]; commands, polls and timers are serialized by [commandMutex] and run in
 * [scope], so a caller's cancellation never aborts a request half-way.
 */
@Singleton
class SpotifyBackend internal constructor(
    private val api: SpotifyPlayerApi,
    private val devices: SpotifyDeviceResolver,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) : PlaybackBackend, BackendPreparer {

    @Inject
    constructor(api: SpotifyPlayerApi, devices: SpotifyDeviceResolver) : this(
        api,
        devices,
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
        SystemClock::elapsedRealtime,
    )

    override val kind: BackendKind = BackendKind.SPOTIFY

    private val _events = MutableSharedFlow<BackendEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<BackendEvent> = _events.asSharedFlow()

    private class Item(val uid: Long, val trackId: String, val durationMs: Long)

    private data class Anchor(val positionMs: Long, val atMs: Long, val playing: Boolean, val durationMs: Long)

    private val commandMutex = Mutex()

    // Run state: confined to [scope], mutated under [commandMutex].
    private var loadId = 0L
    private var holding = false
    /** False while a paused load/seek waits for the first play (no network until then). */
    private var started = false
    private var ended = false
    /** What the Spotify device has loaded, in `uris` order. */
    private var items: List<Item> = emptyList()
    private var currentIndex = -1
    /** Last item allowed to play; the end-of-item pause fires after it. */
    private var stopIndex = -1
    private var pendingPositionMs = 0L
    private var intendedPlaying = false
    private var reportedPlaying = false
    private var reportedBuffering = false
    private var playingSeen = false
    private var lastPlayingRemainingMs = Long.MAX_VALUE
    private var stoppedAtEndPolls = 0
    private var repeatOne = false
    private var shuffleChecked = false
    private var startedAtMs = 0L
    private var startPositionMs = 0L

    private var settleUntilMs = 0L
    private var settleIndex = -1
    private var settlePositionMs = 0L
    private var settleStartedAtMs = 0L

    private var unknownId: String? = null
    private var unknownStreak = 0
    private var nullStreak = 0
    private var manualPauseUntilMs = 0L

    private var stallIndex = -1
    private var stallProgressMs = 0L
    private var stallSinceMs = 0L

    private var recoveryAttempts = 0
    private var lastRecoveryAtMs: Long? = null
    private var recoveryExhausted = false
    private var recoveryFailed = false

    private var desiredVolume: Int? = null
    private var sentVolume: Int? = null

    @Volatile private var anchor = Anchor(0L, 0L, playing = false, durationMs = 0L)

    private var pollerJob: Job? = null
    private val pollWake = Channel<Unit>(Channel.CONFLATED)
    private var endTimerJob: Job? = null
    private var reloadJob: Job? = null
    private var reloadGeneration = 0L
    private var volumeJob: Job? = null
    /** Device of the last successful start; settings target it (Spotify 404s untargeted calls until it's active). */
    private var playingDeviceId: String? = null

    // ---------------------------------------------------------------- commands

    override suspend fun load(loadId: Long, run: Run, startIndex: Int, positionMs: Long, playWhenReady: Boolean) = command {
        val wasAudible = holding && started && intendedPlaying && !ended
        resetRun()
        this.loadId = loadId
        holding = true
        items = run.songs()
        stopIndex = items.lastIndex
        shuffleChecked = false
        if (items.isEmpty()) {
            holding = false
            emit(BackendEvent.Error(loadId, null, "Spotify queue is empty"))
            return@command
        }
        val start = run.items.take(startIndex.coerceIn(0, run.items.size)).count { it is PlanItem.Song }
            .coerceAtMost(items.lastIndex)
        currentIndex = start
        intendedPlaying = playWhenReady
        emit(BackendEvent.ItemStarted(loadId, key(start)))

        if (!playWhenReady) {
            // Restore / paused load: no network until the first play (design §6.1).
            pendingPositionMs = positionMs
            setAnchor(positionMs, playing = false)
            emitPosition()
            if (wasAudible) pauseQuietly()
            return@command
        }

        try {
            val snapshot = pollState()
            shuffleChecked = true
            if (snapshot != null && snapshot.isPlaying && matches(snapshot, items[start]) &&
                abs(snapshot.progressMs - positionMs) <= ADOPT_TOLERANCE_MS
            ) {
                adopt(snapshot)
                return@command
            }
            if (snapshot?.shuffle == true) forceShuffleOff()
            startAt(start, positionMs)
        } catch (e: SpotifyCommandException) {
            fail(e, "Spotify failed to start playback")
            return@command
        }
        afterStart()
    }

    override suspend fun updateUpcoming(loadId: Long, run: Run, currentKey: ItemKey) = command {
        if (loadId != this.loadId || !holding || ended) return@command
        val uid = (currentKey as? ItemKey.Song)?.uid ?: return@command
        val songs = run.songs()
        val runCurrent = songs.indexOfFirst { it.uid == uid }
        if (runCurrent < 0) return@command

        if (!started) {
            // Nothing is on the device yet: the next start simply uses the new list.
            items = songs
            stopIndex = items.lastIndex
            currentIndex = runCurrent
            return@command
        }

        val current = if (items.getOrNull(currentIndex)?.uid == uid) currentIndex else items.indexOfFirst { it.uid == uid }
        if (current < 0) return@command
        val newTail = songs.subList(runCurrent + 1, songs.size)
        val loadedTail = items.subList(current + 1, items.size)
        var common = 0
        while (common < loadedTail.size && common < newTail.size && loadedTail[common].uid == newTail[common].uid) common++

        when {
            // The loaded window is still the run's prefix; edits beyond it need nothing.
            common == loadedTail.size -> {
                cancelReload()
                stopIndex = items.lastIndex
            }
            // The next item within the loaded window changed (play next, shuffle): reload.
            common == 0 && newTail.isNotEmpty() -> {
                stopIndex = current
                scheduleReload(songs, uid)
            }
            // The run ends earlier (break / EXO item next) or diverges later: stop before the
            // divergence; a later updateUpcoming reloads once the divergence becomes "next".
            else -> {
                cancelReload()
                stopIndex = current + common
            }
        }
        armEndTimer()
    }

    override suspend fun seekToItem(key: ItemKey, positionMs: Long) = command {
        if (!holding) return@command
        val uid = (key as? ItemKey.Song)?.uid
        val index = items.indexOfFirst { it.uid == uid }
        if (index < 0) {
            emit(BackendEvent.Error(loadId, key, SpotifyMessages.commandFailed("startQueue")))
            return@command
        }
        cancelReload()
        ended = false
        stopIndex = maxOf(stopIndex, index)
        currentIndex = index
        emit(BackendEvent.ItemStarted(loadId, key(index)))
        if (!started || !intendedPlaying) {
            started = false
            cancelEndTimer()
            pendingPositionMs = positionMs
            setAnchor(positionMs, playing = false)
            emitPosition()
            return@command
        }
        try {
            startAt(index, positionMs)
        } catch (e: SpotifyCommandException) {
            fail(e, "Spotify failed to start playback")
            return@command
        }
        afterStart()
    }

    override suspend fun play() = command {
        if (!holding || ended) return@command
        resetRecovery()
        if (!started) {
            try {
                startAt(currentIndex, pendingPositionMs)
            } catch (e: SpotifyCommandException) {
                fail(e, "Spotify failed to resume playback")
                return@command
            }
            afterStart()
            return@command
        }
        val resumed = try {
            api.resume()
            true
        } catch (_: SpotifyCommandException) {
            false
        }
        if (!resumed) {
            try {
                startAt(currentIndex, positionMs())
            } catch (e: SpotifyCommandException) {
                fail(e, "Spotify failed to resume playback")
                return@command
            }
            afterStart()
            return@command
        }
        manualPauseUntilMs = 0L
        intendedPlaying = true
        setAnchor(positionMs(), playing = true)
        reportPlaying(isPlaying = true, buffering = false, byPlayer = false)
        emitPosition()
        ensurePoller()
        wakePoller()
        armEndTimer()
    }

    override suspend fun pause() = command {
        if (!holding) return@command
        cancelEndTimer()
        if (!started || ended) {
            intendedPlaying = false
            setAnchor(positionMs(), playing = false)
            reportPlaying(isPlaying = false, buffering = false, byPlayer = false)
            return@command
        }
        manualPauseUntilMs = clock() + MANUAL_PAUSE_GRACE_MS
        try {
            api.pause()
        } catch (e: SpotifyCommandException) {
            // Spotify rejects pausing playback that is already paused (403 "Restriction violated"),
            // e.g. after the user paused in the Spotify app; only a still-playing device is a failure.
            // A 204 (no active playback) counts as "not playing" too.
            if (e.httpStatus == null || pollState()?.isPlaying == true) {
                manualPauseUntilMs = 0L
                fail(e, "Spotify failed to pause")
                armEndTimer()
                return@command
            }
        }
        manualPauseUntilMs = clock() + MANUAL_PAUSE_GRACE_MS
        intendedPlaying = false
        resetStall()
        setAnchor(positionMs(), playing = false)
        reportPlaying(isPlaying = false, buffering = false, byPlayer = false)
        emitPosition()
    }

    override suspend fun seekTo(ms: Long) = command {
        if (!holding || ended) return@command
        val target = ms.coerceAtLeast(0L)
        if (!started) {
            pendingPositionMs = target
            setAnchor(target, playing = false)
            emitPosition()
            return@command
        }
        try {
            api.seek(target)
        } catch (e: SpotifyCommandException) {
            fail(e, "Spotify command failed")
            return@command
        }
        beginSettle(currentIndex, target)
        resetStall()
        stoppedAtEndPolls = 0
        setAnchor(target, playing = anchor.playing)
        emitPosition()
        armEndTimer()
    }

    override suspend fun setVolume(effectivePercent: Int) {
        val volume = effectivePercent.coerceIn(0, 100)
        scope.launch {
            desiredVolume = volume
            volumeJob?.cancel()
            volumeJob = scope.launch {
                delay(VOLUME_DEBOUNCE_MS)
                commandMutex.withLock {
                    volumeJob = null
                    if (holding && started && !ended) applyVolume()
                }
            }
        }
    }

    override suspend fun setRepeatOne(on: Boolean) = command {
        repeatOne = on
        if (holding && started && !ended) {
            try {
                applyRepeat()
            } catch (e: SpotifyCommandException) {
                fail(e, "Spotify command failed")
            }
        }
        armEndTimer()
    }

    override suspend fun stop() = command {
        val audible = holding && started && intendedPlaying && !ended
        resetRun()
        holding = false
        intendedPlaying = false
        setAnchor(positionMs(), playing = false)
        if (audible) pauseQuietly()
    }

    /**
     * Pre-resolves the Connect device (launching Spotify while in the foreground) so the next start
     * is a single request. True when a device is ready; never emits backend events.
     */
    override suspend fun prepare(): Boolean = scope.async {
        try {
            prepareDevice()
            true
        } catch (_: SpotifyCommandException) {
            false
        }
    }.await()

    /** Resolves a Connect device ahead of a handoff; failures retain the legacy messages. */
    suspend fun prepareDevice() = command { devices.resolve(); Unit }

    override fun positionMs(): Long {
        val a = anchor
        val position = if (a.playing) a.positionMs + (clock() - a.atMs) else a.positionMs
        return if (a.durationMs > 0) position.coerceIn(0L, a.durationMs) else position.coerceAtLeast(0L)
    }

    override fun durationMs(): Long = anchor.durationMs

    // ---------------------------------------------------------------- start / adopt

    /** Starts the loaded list at [index]; throws [SpotifyCommandException]. Requires [commandMutex]. */
    private suspend fun startAt(index: Int, positionMs: Long) {
        if (!shuffleChecked) {
            // Strict: with device shuffle on, Spotify would play the URI list out of the app's order.
            if (pollState()?.shuffle == true) forceShuffleOff()
            shuffleChecked = true
        }
        val ids = items.map { it.trackId }
        var deviceId = devices.resolve()
        try {
            api.startPlayback(deviceId, ids, index, positionMs)
        } catch (e: SpotifyCommandException) {
            // A cached/pre-resolved device may have gone away: look it up once more.
            devices.invalidate()
            val fresh = devices.resolve()
            if (fresh == deviceId) throw e
            api.startPlayback(fresh, ids, index, positionMs)
            deviceId = fresh
        }
        playingDeviceId = deviceId
        started = true
        ended = false
        intendedPlaying = true
        manualPauseUntilMs = 0L
        currentIndex = index
        playingSeen = false
        lastPlayingRemainingMs = Long.MAX_VALUE
        stoppedAtEndPolls = 0
        unknownId = null
        unknownStreak = 0
        nullStreak = 0
        startedAtMs = clock()
        startPositionMs = positionMs
        resetStall()
        beginSettle(index, positionMs)
        setAnchor(positionMs, playing = false)
        // The track is already playing: settings Spotify rejects while the device activates must
        // not report the start as failed.
        applyRepeatQuietly()
        applyVolume()
    }

    private fun afterStart() {
        reportPlaying(isPlaying = true, buffering = true, byPlayer = false)
        emitPosition()
        ensurePoller()
        armEndTimer()
    }

    /** The device already plays the target near the requested position: take it over as-is. */
    private suspend fun adopt(snapshot: SpotifyPlaybackSnapshot) {
        started = true
        playingDeviceId = null // already playing: untargeted calls reach the active device
        manualPauseUntilMs = 0L
        playingSeen = true
        startedAtMs = clock()
        startPositionMs = snapshot.progressMs
        if (snapshot.shuffle) forceShuffleOff()
        applyRepeatQuietly()
        applyVolume()
        setAnchor(snapshot.progressMs, playing = true, durationMs = durationFor(snapshot, currentIndex))
        reportPlaying(isPlaying = true, buffering = false, byPlayer = false)
        emitPosition()
        ensurePoller()
        armEndTimer()
    }

    // ---------------------------------------------------------------- polling

    private fun ensurePoller() {
        if (pollerJob?.isActive == true) return
        pollerJob = scope.launch {
            while (true) {
                val wait = pollDelayMs() ?: break
                withTimeoutOrNull(wait) { pollWake.receive() }
                commandMutex.withLock { pollOnce() }
            }
        }
    }

    private fun wakePoller() {
        pollWake.trySend(Unit)
    }

    /** 1 s when <5 s remain, 2 s while playing (or starting), 5 s while paused; null = stop. */
    private fun pollDelayMs(): Long? {
        if (!holding || !started || ended || recoveryExhausted) return null
        val a = anchor
        if (!a.playing) return if (intendedPlaying) POLL_PLAYING_MS else POLL_PAUSED_MS
        val remaining = a.durationMs - positionMs()
        return if (a.durationMs > 0 && remaining < NEAR_END_POLL_WINDOW_MS) POLL_NEAR_END_MS else POLL_PLAYING_MS
    }

    private suspend fun pollState(): SpotifyPlaybackSnapshot? =
        try {
            api.playbackState()
        } catch (_: SpotifyCommandException) {
            null
        }

    private suspend fun pollOnce() {
        if (!holding || !started || ended) return
        onPoll(pollState())
    }

    private suspend fun onPoll(snapshot: SpotifyPlaybackSnapshot?) {
        if (!holding || !started || ended || recoveryExhausted) return
        val now = clock()
        if (now < manualPauseUntilMs) return
        if (snapshot == null) {
            nullStreak++
            if (intendedPlaying && now >= settleUntilMs && nullStreak >= NULL_POLLS_FOR_LOSS) {
                recover(REASON_SNAPSHOT_LOST, positionMs())
            }
            return
        }
        nullStreak = 0

        val inSettle = now < settleUntilMs
        val index = indexOf(snapshot, if (inSettle) settleIndex else currentIndex)
        if (inSettle) {
            if (index != settleIndex || !settleProgressMatches(snapshot, now)) return
            settleUntilMs = 0L
        }

        if (intendedPlaying && !playingSeen && now - startedAtMs >= STALL_MS) {
            recover(REASON_STALLED, startPositionMs)
            return
        }

        if (index == null) {
            val id = snapshot.trackId.orEmpty()
            if (id == unknownId) unknownStreak++ else {
                unknownId = id
                unknownStreak = 1
            }
            if (unknownStreak >= UNKNOWN_POLLS_FOR_EXIT && playingSeen) endRun()
            return
        }
        unknownId = null
        unknownStreak = 0

        if (index < currentIndex && !inSettle) {
            // Natural playback never goes backwards: after the last uri Spotify resets to the
            // first one, paused.
            if (!snapshot.isPlaying && playingSeen && currentIndex >= stopIndex) endRun()
            return
        }
        if (index > stopIndex) {
            // Crossed the stop point (the end-of-item timer was late).
            finishAtBoundary()
            return
        }

        var trackChanged = false
        if (index != currentIndex) {
            currentIndex = index
            playingSeen = false
            lastPlayingRemainingMs = Long.MAX_VALUE
            stoppedAtEndPolls = 0
            resetStall()
            trackChanged = true
            emit(BackendEvent.ItemStarted(loadId, key(index)))
        }

        val duration = durationFor(snapshot, index)
        if (snapshot.isPlaying) {
            playingSeen = true
            // An accepted restart or a frozen is_playing response is not recovery evidence.
            if (recoveryAttempts > 0 && (trackChanged || snapshot.progressMs > startPositionMs)) resetRecovery()
            recoveryFailed = false
            lastPlayingRemainingMs = if (duration > 0) duration - snapshot.progressMs else Long.MAX_VALUE
        }
        if (snapshot.shuffle) forceShuffleOffQuietly()

        if (!snapshot.isPlaying && playingSeen && !repeatOne && endedNaturally(snapshot, duration)) {
            if (index >= stopIndex) {
                endRun()
                return
            }
            if (intendedPlaying && ++stoppedAtEndPolls >= STOPPED_AT_END_POLLS) {
                continueAt(index + 1)
                return
            }
        } else {
            stoppedAtEndPolls = 0
        }

        var stateChanged = false
        if (!snapshot.isPlaying) {
            if (recoveryFailed && intendedPlaying) {
                recover(REASON_ERROR_STATE, positionMs())
                return
            }
            val inTrackGap = intendedPlaying && duration > 0 && snapshot.progressMs >= duration - TRACK_GAP_MS
            if (playingSeen && intendedPlaying && !inTrackGap) {
                // Paused outside the app (Spotify app, another controller): adopt it.
                intendedPlaying = false
                stateChanged = reportPlaying(isPlaying = false, buffering = false, byPlayer = true)
            }
        } else {
            stateChanged = reportPlaying(isPlaying = true, buffering = false, byPlayer = !intendedPlaying)
            intendedPlaying = true
        }

        if (snapshot.isPlaying) {
            if (stallIndex == index && snapshot.progressMs == stallProgressMs) {
                if (now - stallSinceMs >= STALL_MS) {
                    recover(REASON_STALLED, snapshot.progressMs)
                    return
                }
            } else {
                stallIndex = index
                stallProgressMs = snapshot.progressMs
                stallSinceMs = now
            }
        } else {
            resetStall()
        }

        val drift = abs(positionMs() - snapshot.progressMs)
        if (trackChanged || stateChanged || drift > DRIFT_TOLERANCE_MS || anchor.playing != snapshot.isPlaying ||
            anchor.durationMs != duration
        ) {
            setAnchor(snapshot.progressMs, playing = snapshot.isPlaying, durationMs = duration)
            if (trackChanged || stateChanged || drift > DRIFT_TOLERANCE_MS) emitPosition()
        }
        armEndTimer()
    }

    // ---------------------------------------------------------------- end of item / run

    private fun armEndTimer() {
        cancelEndTimer()
        if (!holding || !started || ended || repeatOne || currentIndex != stopIndex) return
        val a = anchor
        if (!a.playing || a.durationMs <= 0) return
        val wait = (a.durationMs - positionMs() - END_LEAD_MS).coerceAtLeast(0L)
        val armedFor = loadId
        endTimerJob = scope.launch {
            delay(wait)
            commandMutex.withLock {
                endTimerJob = null
                if (armedFor == loadId) onEndTimer()
            }
        }
    }

    private fun cancelEndTimer() {
        endTimerJob?.cancel()
        endTimerJob = null
    }

    /** Forced poll at `remaining − 300 ms` of the stop item; pauses there and ends the run. */
    private suspend fun onEndTimer() {
        if (!holding || !started || ended || currentIndex != stopIndex) return
        val snapshot = pollState()
        if (snapshot == null) {
            finishAtBoundary()
            return
        }
        val index = indexOf(snapshot, currentIndex)
        if (index == currentIndex && snapshot.isPlaying) {
            val remaining = durationFor(snapshot, currentIndex) - snapshot.progressMs
            if (remaining > END_LEAD_MS + END_TOLERANCE_MS) {
                onPoll(snapshot)
                return
            }
            playingSeen = true
            finishAtBoundary()
            return
        }
        if (index != null && index > currentIndex) {
            finishAtBoundary()
            return
        }
        onPoll(snapshot)
    }

    private fun endRun() {
        if (!playingSeen || ended) return
        ended = true
        cancelEndTimer()
        cancelReload()
        setAnchor(positionMs(), playing = false)
        emit(BackendEvent.RunEnded(loadId))
    }

    /** Do not hand audio to another backend unless Spotify actually stopped this item. */
    private suspend fun finishAtBoundary() {
        if (!playingSeen) return
        val rejection = pauseAtBoundary() ?: return endRun()
        // At the very end of a track Spotify rejects the pause (403/404) while the context ends,
        // and GET me/player can still report the old track for a moment. Let the end pass, then
        // look again and retry once before calling it a failure.
        if (rejection.httpStatus == null) return fail(rejection, "Spotify failed to pause")
        delay(BOUNDARY_RETRY_DELAY_MS)
        if (!spotifyStillAudible()) return endRun()
        val retry = pauseAtBoundary() ?: return endRun()
        if (retry.httpStatus != null && !spotifyStillAudible()) return endRun()
        fail(retry, "Spotify failed to pause")
    }

    /** Null when Spotify accepted the pause. */
    private suspend fun pauseAtBoundary(): SpotifyCommandException? =
        try {
            api.pause()
            null
        } catch (e: SpotifyCommandException) {
            e
        }

    /**
     * Whether Spotify would keep playing over the next backend: anything audible except the last
     * moments of the current item (Spotify moving on to a later uri or autoplay counts as audible).
     */
    private suspend fun spotifyStillAudible(): Boolean {
        val snapshot = pollState() ?: return false
        if (!snapshot.isPlaying) return false
        if (indexOf(snapshot, currentIndex) != currentIndex) return true
        val duration = durationFor(snapshot, currentIndex)
        return duration <= 0 || snapshot.progressMs < duration - END_LEAD_MS
    }

    /** Spotify stopped at the end of a mid-run item: continue with the next one. */
    private suspend fun continueAt(index: Int) {
        try {
            startAt(index, 0L)
        } catch (e: SpotifyCommandException) {
            fail(e, "Spotify failed to start playback")
            return
        }
        emit(BackendEvent.ItemStarted(loadId, key(index)))
        afterStart()
    }

    // ---------------------------------------------------------------- reload

    private fun scheduleReload(songs: List<Item>, uid: Long) {
        reloadJob?.cancel()
        val generation = ++reloadGeneration
        val forLoad = loadId
        reloadJob = scope.launch {
            delay(RELOAD_DEBOUNCE_MS)
            commandMutex.withLock {
                reloadJob = null
                if (generation == reloadGeneration && forLoad == loadId && holding && !ended) reload(songs, uid)
            }
        }
    }

    private fun cancelReload() {
        reloadGeneration++
        reloadJob?.cancel()
        reloadJob = null
    }

    private suspend fun reload(songs: List<Item>, uid: Long) {
        val index = songs.indexOfFirst { it.uid == uid }
        if (index < 0) return
        val position = positionMs()
        items = songs
        stopIndex = items.lastIndex
        currentIndex = index
        if (!started || !intendedPlaying) {
            started = false
            cancelEndTimer()
            pendingPositionMs = position
            return
        }
        try {
            startAt(index, position)
        } catch (e: SpotifyCommandException) {
            fail(e, "Spotify failed to start playback")
            return
        }
        setAnchor(position, playing = true)
        armEndTimer()
    }

    // ---------------------------------------------------------------- recovery

    private suspend fun recover(reason: String, positionMs: Long) {
        if (recoveryExhausted || !intendedPlaying) return
        val now = clock()
        val cooldown = if (recoveryAttempts == 0) RECOVERY_FIRST_COOLDOWN_MS else RECOVERY_COOLDOWN_MS
        val last = lastRecoveryAtMs
        if (last != null && now - last < cooldown) return
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            recoveryExhausted = true
            intendedPlaying = false
            resetStall()
            cancelEndTimer()
            setAnchor(positionMs, playing = false)
            reportedPlaying = false
            reportedBuffering = false
            emit(BackendEvent.Error(loadId, key(currentIndex), SpotifyMessages.recoveryExhausted(reason)))
            return
        }
        recoveryAttempts++
        lastRecoveryAtMs = now
        try {
            startAt(currentIndex.coerceAtLeast(0), positionMs)
            recoveryFailed = false
        } catch (_: SpotifyCommandException) {
            recoveryFailed = true
            return
        }
        afterStart()
    }

    private fun resetRecovery() {
        recoveryAttempts = 0
        lastRecoveryAtMs = null
        recoveryExhausted = false
        recoveryFailed = false
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun <T> command(block: suspend () -> T): T =
        scope.async { commandMutex.withLock { block() } }.await()

    private fun resetRun() {
        pollerJob?.cancel()
        pollerJob = null
        cancelEndTimer()
        cancelReload()
        started = false
        ended = false
        items = emptyList()
        currentIndex = -1
        stopIndex = -1
        pendingPositionMs = 0L
        playingSeen = false
        lastPlayingRemainingMs = Long.MAX_VALUE
        stoppedAtEndPolls = 0
        reportedPlaying = false
        reportedBuffering = false
        settleUntilMs = 0L
        unknownId = null
        unknownStreak = 0
        nullStreak = 0
        resetStall()
        resetRecovery()
    }

    private fun resetStall() {
        stallIndex = -1
        stallProgressMs = 0L
        stallSinceMs = 0L
    }

    private fun beginSettle(index: Int, positionMs: Long) {
        settleIndex = index
        settlePositionMs = positionMs
        settleStartedAtMs = clock()
        settleUntilMs = settleStartedAtMs + SETTLE_WINDOW_MS
    }

    private fun settleProgressMatches(snapshot: SpotifyPlaybackSnapshot, now: Long): Boolean {
        val expected = settlePositionMs + if (snapshot.isPlaying) now - settleStartedAtMs else 0L
        return abs(snapshot.progressMs - expected) <= SETTLE_POSITION_TOLERANCE_MS
    }

    private fun endedNaturally(snapshot: SpotifyPlaybackSnapshot, duration: Long): Boolean =
        (duration > 0 && snapshot.progressMs >= duration - END_TOLERANCE_WINDOW_MS) ||
            (snapshot.progressMs <= END_TOLERANCE_WINDOW_MS && lastPlayingRemainingMs <= NEAR_END_POLL_WINDOW_MS)

    /** Matches `item.id` or `item.linked_from.id`; searches [preferred], then forward, then backward. */
    private fun indexOf(snapshot: SpotifyPlaybackSnapshot, preferred: Int): Int? {
        if (snapshot.trackId == null && snapshot.linkedFromId == null) return null
        val start = preferred.coerceIn(0, items.lastIndex.coerceAtLeast(0))
        for (i in start until items.size) if (matches(snapshot, items[i])) return i
        for (i in start - 1 downTo 0) if (matches(snapshot, items[i])) return i
        return null
    }

    private fun matches(snapshot: SpotifyPlaybackSnapshot, item: Item): Boolean =
        snapshot.trackId == item.trackId || (snapshot.linkedFromId != null && snapshot.linkedFromId == item.trackId)

    private fun durationFor(snapshot: SpotifyPlaybackSnapshot, index: Int): Long =
        snapshot.durationMs.takeIf { it > 0 } ?: items.getOrNull(index)?.durationMs ?: 0L

    private fun key(index: Int): ItemKey.Song = ItemKey.Song(items[index].uid)

    private fun setAnchor(positionMs: Long, playing: Boolean, durationMs: Long = items.getOrNull(currentIndex)?.durationMs ?: 0L) {
        val duration = if (durationMs > 0) durationMs else anchor.durationMs.takeIf { playing || it > 0 } ?: 0L
        anchor = Anchor(positionMs.coerceAtLeast(0L), clock(), playing, duration)
    }

    private fun emitPosition() {
        emit(BackendEvent.PositionDiscontinuity(loadId, positionMs(), anchor.durationMs))
    }

    /** Emits on a change of (playing, buffering); returns whether it changed. */
    private fun reportPlaying(isPlaying: Boolean, buffering: Boolean, byPlayer: Boolean): Boolean {
        if (isPlaying == reportedPlaying && buffering == reportedBuffering) return false
        reportedPlaying = isPlaying
        reportedBuffering = buffering
        emit(BackendEvent.PlayingChanged(loadId, isPlaying, buffering, byPlayer))
        return true
    }

    private fun fail(error: SpotifyCommandException, fallback: String) {
        val index = currentIndex
        emit(BackendEvent.Error(loadId, items.getOrNull(index)?.let { ItemKey.Song(it.uid) }, error.message ?: fallback))
    }

    private fun emit(event: BackendEvent) {
        _events.tryEmit(event)
    }

    private suspend fun pauseQuietly() {
        manualPauseUntilMs = clock() + MANUAL_PAUSE_GRACE_MS
        try {
            api.pause()
        } catch (_: SpotifyCommandException) {
            manualPauseUntilMs = 0L
        }
    }

    /**
     * The app owns play order, so device shuffle is switched off before a start; a failure aborts
     * the start (throws) because Spotify would otherwise play the run out of order.
     */
    private suspend fun forceShuffleOff() {
        api.setShuffle(false, playingDeviceId)
        shuffleChecked = true
    }

    /** Mid-run re-check (user turned shuffle on in Spotify): the next poll retries, so stay quiet. */
    private suspend fun forceShuffleOffQuietly() {
        try {
            forceShuffleOff()
        } catch (_: SpotifyCommandException) {
        }
    }

    /** Repeat applied automatically on start/adopt; a rejection is not worth an error. */
    private suspend fun applyRepeatQuietly() {
        try {
            applyRepeat()
        } catch (_: SpotifyCommandException) {
        }
    }

    private suspend fun applyRepeat() {
        api.setRepeat(if (repeatOne) RepeatMode.ONE else RepeatMode.OFF, playingDeviceId)
    }

    private suspend fun applyVolume() {
        val volume = desiredVolume ?: return
        if (volume == sentVolume) return
        try {
            api.setVolume(volume, playingDeviceId)
            sentVolume = volume
        } catch (_: SpotifyCommandException) {
            sentVolume = null
        }
    }

    private fun Run.songs(): List<Item> = items.mapNotNull { item ->
        (item as? PlanItem.Song)?.let {
            Item(it.entry.uid, normalizeSpotifyTrackId(it.entry.track.id), it.entry.track.durationMs ?: 0L)
        }
    }

    internal companion object {
        const val ADOPT_TOLERANCE_MS = 3_000L
        const val SETTLE_WINDOW_MS = 3_000L
        const val SETTLE_POSITION_TOLERANCE_MS = 3_000L
        const val POLL_NEAR_END_MS = 1_000L
        const val POLL_PLAYING_MS = 2_000L
        const val POLL_PAUSED_MS = 5_000L
        const val NEAR_END_POLL_WINDOW_MS = 5_000L
        const val END_LEAD_MS = 300L
        const val END_TOLERANCE_MS = 700L
        const val END_TOLERANCE_WINDOW_MS = 2_000L
        const val TRACK_GAP_MS = 1_500L
        const val STOPPED_AT_END_POLLS = 2
        const val UNKNOWN_POLLS_FOR_EXIT = 2
        const val NULL_POLLS_FOR_LOSS = 3
        const val DRIFT_TOLERANCE_MS = 750L
        const val STALL_MS = 10_000L
        const val MANUAL_PAUSE_GRACE_MS = 5_000L
        const val MAX_RECOVERY_ATTEMPTS = 3
        const val RECOVERY_FIRST_COOLDOWN_MS = 2_500L
        const val RECOVERY_COOLDOWN_MS = 4_000L
        const val RELOAD_DEBOUNCE_MS = 300L
        const val BOUNDARY_RETRY_DELAY_MS = 1_000L
        const val VOLUME_DEBOUNCE_MS = 300L
        const val REASON_SNAPSHOT_LOST = "Spotify snapshot unavailable after interruption"
        const val REASON_STALLED = "Spotify playback stalled while reporting playing"
        const val REASON_ERROR_STATE = "Spotify failed to recover from error state"
    }
}
