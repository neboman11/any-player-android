package com.anyplayer.android.playback.spotify

import com.anyplayer.android.core.model.RepeatMode
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory Spotify Connect device on a virtual clock: plays the started `uris` in order,
 * advances natively and, after the last one, resets to the first uri paused (like Spotify).
 */
class FakeSpotifyPlayerApi(private val clock: () -> Long) : SpotifyPlayerApi {
    val calls = mutableListOf<String>()
    val devicesList = mutableListOf(SpotifyDevice("phone", "Phone", "Smartphone", isActive = true, isRestricted = false))
    val durations = mutableMapOf<String, Long>()
    var defaultDurationMs = 200_000L

    /** Track id → relinked id reported as `item.id` (with the original in `linked_from`). */
    val relinked = mutableMapOf<String, String>()

    var available = true
    var shuffle = false
    var repeat = RepeatMode.OFF
    var volume = 50
    /** Progress stops advancing while still reporting `is_playing` (ghost session). */
    var frozen = false
        set(value) {
            settle()
            field = value
        }
    /** A started context only becomes visible after this delay (device lag). */
    var startDelayMs = 0L
    /** Remaining failures per action name ("startQueue", "play", "pause", …). */
    val failures = mutableMapOf<String, Int>()
    var startGate: CompletableDeferred<Unit>? = null

    private var uris: List<String> = emptyList()
    private var index = 0
    private var playing = false
    private var baseProgress = 0L
    private var baseAt = 0L
    private var foreignTrack: String? = null
    private var pendingStart: Triple<List<String>, Int, Long>? = null
    private var pendingAt = 0L

    /** Puts the device on [trackIds] at [index] as if started elsewhere. */
    fun setPlaying(trackIds: List<String>, index: Int, progressMs: Long, isPlaying: Boolean = true) {
        settle()
        uris = trackIds
        this.index = index
        foreignTrack = null
        playing = isPlaying
        baseProgress = progressMs
        baseAt = clock()
    }

    /** The device switches to a track that is not part of the started list. */
    fun playForeign(trackId: String) {
        settle()
        foreignTrack = trackId
        playing = true
        baseProgress = 0
        baseAt = clock()
    }

    fun externalPause() {
        settle()
        playing = false
    }

    fun currentTrack(): String? {
        settle()
        return foreignTrack ?: uris.getOrNull(index)
    }

    fun progressNow(): Long {
        settle()
        return baseProgress
    }

    override suspend fun playbackState(): SpotifyPlaybackSnapshot? {
        calls += "state"
        if (!available) return null
        settle()
        val id = foreignTrack ?: uris.getOrNull(index) ?: return null
        val reported = relinked[id]
        return SpotifyPlaybackSnapshot(
            isPlaying = playing,
            progressMs = baseProgress,
            durationMs = duration(id),
            trackId = reported ?: id,
            linkedFromId = if (reported != null) id else null,
            volumePercent = volume,
            shuffle = shuffle,
            repeat = repeat,
        )
    }

    override suspend fun devices(): List<SpotifyDevice> {
        calls += "devices"
        return devicesList.toList()
    }

    override suspend fun startPlayback(deviceId: String?, trackIds: List<String>, offset: Int, positionMs: Long) {
        calls += "start:$deviceId:$offset:$positionMs:${trackIds.joinToString(",")}"
        startGate?.await()
        maybeFail("startQueue")
        settle()
        if (startDelayMs > 0) {
            pendingStart = Triple(trackIds, offset, positionMs)
            pendingAt = clock() + startDelayMs
        } else {
            applyStart(trackIds, offset, positionMs, clock())
        }
    }

    override suspend fun resume() {
        calls += "resume"
        maybeFail("play")
        settle()
        playing = true
        baseAt = clock()
    }

    override suspend fun pause() {
        calls += "pause"
        maybeFail("pause")
        settle()
        playing = false
    }

    override suspend fun seek(positionMs: Long) {
        calls += "seek:$positionMs"
        maybeFail("seek")
        settle()
        baseProgress = positionMs
        baseAt = clock()
    }

    override suspend fun setVolume(percent: Int) {
        calls += "volume:$percent"
        maybeFail("setVolume")
        volume = percent
    }

    override suspend fun setShuffle(enabled: Boolean) {
        calls += "shuffle:$enabled"
        maybeFail("setShuffle")
        shuffle = enabled
    }

    override suspend fun setRepeat(mode: RepeatMode) {
        calls += "repeat:$mode"
        maybeFail("setRepeatMode")
        repeat = mode
    }

    fun startCalls(): List<String> = calls.filter { it.startsWith("start:") }

    private fun maybeFail(action: String) {
        val remaining = failures[action] ?: return
        if (remaining <= 0) return
        failures[action] = remaining - 1
        throw SpotifyCommandException(SpotifyMessages.commandFailed(action))
    }

    private fun duration(id: String): Long = durations[id] ?: defaultDurationMs

    private fun applyStart(trackIds: List<String>, offset: Int, positionMs: Long, at: Long) {
        uris = trackIds
        index = offset
        foreignTrack = null
        playing = true
        baseProgress = positionMs
        baseAt = at
    }

    /** Advances the simulated device to the current virtual time. */
    private fun settle() {
        val now = clock()
        pendingStart?.let { (ids, offset, position) ->
            if (now >= pendingAt) {
                advanceTo(pendingAt)
                pendingStart = null
                applyStart(ids, offset, position, pendingAt)
            }
        }
        advanceTo(now)
    }

    private fun advanceTo(now: Long) {
        if (!playing || frozen) {
            baseAt = now
            return
        }
        var progress = baseProgress + (now - baseAt)
        val foreign = foreignTrack
        if (foreign != null) {
            baseProgress = progress.coerceAtMost(duration(foreign))
            baseAt = now
            return
        }
        while (uris.isNotEmpty() && repeat != RepeatMode.ONE && progress >= duration(uris[index])) {
            progress -= duration(uris[index])
            if (index == uris.lastIndex) {
                index = 0
                playing = false
                progress = 0
                break
            }
            index++
        }
        if (repeat == RepeatMode.ONE && uris.isNotEmpty()) progress %= duration(uris[index])
        baseProgress = progress
        baseAt = now
    }
}

class FakeSpotifyAppLauncher(
    var foreground: Boolean = true,
    var installed: Boolean = true,
    private val onLaunch: () -> Unit = {},
) : SpotifyAppLauncher {
    var launches = 0

    override fun isAppInForeground(): Boolean = foreground

    override fun launchSpotify(): Boolean {
        launches++
        if (!installed) return false
        onLaunch()
        return true
    }
}
