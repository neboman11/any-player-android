package com.anyplayer.android.playback.session

import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import com.anyplayer.android.core.model.*
import com.anyplayer.android.playback.PlaybackEngine
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlin.math.abs

/** A paged play-order projection; no decoder and no position polling. */
@OptIn(UnstableApi::class)
class SessionPlayer(private val engine: PlaybackEngine) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mediaItems = HashMap<Long, MediaItem>()
    private val itemData = HashMap<Long, MediaItemData>()
    private var version = -1L
    private var start = 0
    private var end = -1
    private var window = emptyList<MediaItemData>()
    private var windowKeys = emptyList<Any>()
    private var breakId: String? = null
    private var breakDuration = -1L
    private var cachedCurrentDuration = -1L
    private var cachedCurrentUid: Long? = null
    private var lastAnchorUid: Long? = null
    private var lastAnchorPositionMs = 0L
    private var lastAnchorElapsedMs = 0L
    private var lastAnchorPlaying = false
    init { scope.launch { combine(engine.queue, engine.player) { _, _ -> }.collect { invalidateState() } } }
    override fun getState(): State {
        val q = engine.queue.value; val p = engine.player.value
        val builder = State.Builder().setAvailableCommands(COMMANDS)
        if (q.current == null) return builder.setPlaybackState(Player.STATE_IDLE).build()
        val activeBreak = p.activeBreak
        val changed = version != q.contentVersion
        if (changed) {
            val uids = q.entries.mapTo(HashSet()) { it.uid }
            mediaItems.keys.retainAll(uids); itemData.keys.retainAll(uids)
        }
        if (changed || q.cursor !in (start + 25)..(end - 25) && !(start == 0 && q.cursor < 25) && !(end == q.order.lastIndex && q.cursor > end - 25) || end < start) {
            start = (q.cursor - 100).coerceAtLeast(0)
            end = (q.cursor + 400).coerceAtMost(q.order.lastIndex)
            version = q.contentVersion
            window = (start..end).map { index -> data(q.entryAt(index)) }
            windowKeys = (start..end).map { q.entryAt(it).uid }
            breakId = null
        }
        if (cachedCurrentUid != p.currentUid || cachedCurrentDuration != p.durationMs) {
            cachedCurrentUid = p.currentUid; cachedCurrentDuration = p.durationMs
            val entry = q.current!!
            if (p.activeBreak == null && p.durationMs > 0) {
                val updated = MediaItemData.Builder(entry.uid).setMediaItem(mediaItems.getOrPut(entry.uid) { entry.toMediaItem() })
                    .setDurationUs(p.durationMs * 1000).setIsSeekable(true).build()
                itemData[entry.uid] = updated
                window = window.map { if (it.uid == entry.uid) updated else it }
            }
        }
        if (breakId != activeBreak?.breakId || breakDuration != (activeBreak?.durationMs ?: -1L)) {
            window = (start..end).map { data(q.entryAt(it)) }
            windowKeys = (start..end).map { q.entryAt(it).uid }
            breakId = activeBreak?.breakId; breakDuration = activeBreak?.durationMs ?: -1
            activeBreak?.let {
                val id = "dj-filler:${it.breakId}"
                val item = MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle("AnyPlayer DJ").setIsPlayable(true).setIsBrowsable(false).build()).build()
                val dj = MediaItemData.Builder(id).setMediaItem(item).setIsSeekable(true)
                    .setDurationUs(it.durationMs.takeIf { d -> d > 0 }?.times(1000) ?: C.TIME_UNSET).build()
                val insertion = (q.cursor - start + 1).coerceIn(0, window.size)
                window = window.toMutableList().apply { add(insertion, dj) }
                windowKeys = windowKeys.toMutableList().apply { add(insertion, id) }
            }
        }
        val currentIndex = if (activeBreak != null) windowKeys.indexOf("dj-filler:${activeBreak.breakId}") else windowKeys.indexOf(q.current!!.uid)
        // Engine-originated seeks (UI, sync, restart-on-previous) only move the anchor, which SimpleBasePlayer cannot see
        // through the position supplier; report a seek discontinuity when the anchor jumps within the same item.
        if (p.anchorPositionMs != lastAnchorPositionMs || p.anchorElapsedRealtimeMs != lastAnchorElapsedMs || p.currentUid != lastAnchorUid) {
            val predicted = lastAnchorPositionMs + if (lastAnchorPlaying) p.anchorElapsedRealtimeMs - lastAnchorElapsedMs else 0
            if (p.currentUid == lastAnchorUid && activeBreak == null && abs(p.anchorPositionMs - predicted) > SEEK_TOLERANCE_MS) {
                builder.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK, p.anchorPositionMs)
            }
            lastAnchorUid = p.currentUid; lastAnchorPositionMs = p.anchorPositionMs; lastAnchorElapsedMs = p.anchorElapsedRealtimeMs
        }
        lastAnchorPlaying = p.status == PlaybackStateType.PLAYING
        return builder.setPlaylist(window).setCurrentMediaItemIndex(currentIndex.coerceAtLeast(0))
            .setPlayWhenReady(p.playRequested, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(when {
                p.status == PlaybackStateType.ERROR || p.status == PlaybackStateType.IDLE -> Player.STATE_IDLE
                p.status == PlaybackStateType.BUFFERING || p.playRequested && p.status != PlaybackStateType.PLAYING -> Player.STATE_BUFFERING
                else -> Player.STATE_READY
            })
            .setPlayerError(if (p.status == PlaybackStateType.ERROR) PlaybackException(p.error ?: "Playback failed", null, PlaybackException.ERROR_CODE_UNSPECIFIED) else null)
            .setShuffleModeEnabled(q.shuffle)
            .setRepeatMode(when (q.repeat) { RepeatMode.OFF -> Player.REPEAT_MODE_OFF; RepeatMode.ONE -> Player.REPEAT_MODE_ONE; RepeatMode.ALL -> Player.REPEAT_MODE_ALL })
            .setVolume(p.volume / 100f).setContentPositionMs { engine.positionMs() }.build()
    }
    private fun data(entry: QueueEntry): MediaItemData = itemData.getOrPut(entry.uid) {
        MediaItemData.Builder(entry.uid).setMediaItem(mediaItems.getOrPut(entry.uid) { entry.toMediaItem() })
            .setDurationUs(entry.track.durationMs?.takeIf { it > 0 }?.times(1000) ?: C.TIME_UNSET).setIsSeekable(true).build()
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) engine.play() else engine.pause(); return Futures.immediateVoidFuture()
    }
    override fun handleStop(): ListenableFuture<*> { engine.pause(); return Futures.immediateVoidFuture() }
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> { engine.setShuffle(shuffleModeEnabled); return Futures.immediateVoidFuture() }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        engine.setRepeat(when (repeatMode) { Player.REPEAT_MODE_ONE -> RepeatMode.ONE; Player.REPEAT_MODE_ALL -> RepeatMode.ALL; else -> RepeatMode.OFF })
        return Futures.immediateVoidFuture()
    }
    override fun handleSetVolume(volume: Float): ListenableFuture<*> { engine.setVolume((volume * 100).toInt()); return Futures.immediateVoidFuture() }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val position = if (positionMs == C.TIME_UNSET) 0 else positionMs
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> engine.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> engine.previous()
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> engine.seekTo(position)
            else -> when (val uid = windowKeys.getOrNull(mediaItemIndex)) {
                is Long -> { if (uid != engine.queue.value.current?.uid || engine.player.value.activeBreak != null) engine.playEntry(uid); engine.seekTo(position) }
                is String -> engine.seekTo(position)
            }
        }
        return Futures.immediateVoidFuture()
    }
    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val q = engine.queue.value
        val resolved = mediaItems.mapNotNull { requested -> q.entries.firstOrNull { it.uid.toString() == requested.mediaId }
            ?: q.entries.firstOrNull { trackIdsMatch(it.track.id, requested.mediaId) } }
        if (resolved.isNotEmpty() && resolved.map { it.uid }.toSet() != q.entries.map { it.uid }.toSet()) {
            engine.setQueue(resolved.map { it.track }, startIndex.coerceIn(resolved.indices), autoPlay = false)
            if (startPositionMs != C.TIME_UNSET) engine.seekTo(startPositionMs)
        }
        return Futures.immediateVoidFuture()
    }
    override fun handleRelease(): ListenableFuture<*> { scope.cancel(); return Futures.immediateVoidFuture() }
    companion object {
        private const val SEEK_TOLERANCE_MS = 1000L
        private val COMMANDS = Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_VOLUME, Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS).build()
    }
}

fun QueueEntry.toMediaItem(): MediaItem {
    val track = track
    return MediaItem.Builder().setMediaId(uid.toString()).apply { track.url?.let { setUri(it) } }
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
            .setArtworkUri(track.imageUrl?.toUri()).setIsBrowsable(false).setIsPlayable(true).build()).build()
}
