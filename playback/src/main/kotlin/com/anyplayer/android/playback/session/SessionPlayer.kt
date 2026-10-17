package com.anyplayer.android.playback.session

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.core.net.toUri
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.QueueEntry
import com.anyplayer.android.playback.PlaybackEngine
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * MediaSession-facing player derived from engine state. It holds no playback of its own:
 * [getState] maps `queue`/`player` and every command is forwarded to the [PlaybackEngine].
 * State is invalidated only when the engine flows emit; position is read on demand through
 * the extrapolating [PositionSupplier].
 */
@OptIn(UnstableApi::class)
class SessionPlayer(
    private val engine: PlaybackEngine,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Uids of the published playlist window, index-aligned with the session timeline. */
    private var windowUids: List<Long> = emptyList()

    init {
        scope.launch {
            combine(engine.queue, engine.player) { _, _ -> }.collect { invalidateState() }
        }
    }

    override fun getState(): State {
        val q = engine.queue.value
        val p = engine.player.value
        val builder = State.Builder().setAvailableCommands(COMMANDS)
        val current = q.current
        if (current == null) {
            windowUids = emptyList()
            return builder.setPlaybackState(Player.STATE_IDLE).build()
        }
        val start = (q.cursor - WINDOW_BEFORE).coerceAtLeast(0)
        val end = (q.cursor + WINDOW_AFTER).coerceAtMost(q.order.lastIndex)
        val window = (start..end).map(q::entryAt)
        windowUids = window.map { it.uid }
        val playlist = window.map { entry ->
            val durationMs = if (entry.uid == p.currentUid && p.durationMs > 0) p.durationMs else entry.track.durationMs
            MediaItemData.Builder(entry.uid)
                .setMediaItem(entry.toMediaItem())
                .setDurationUs(durationMs?.takeIf { it > 0 }?.times(1000) ?: C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        val playWhenReady = p.playRequested
        return builder
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(q.cursor - start)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                when {
                    p.status == PlaybackStateType.ERROR || p.status == PlaybackStateType.IDLE -> Player.STATE_IDLE
                    p.status == PlaybackStateType.BUFFERING ||
                        (p.playRequested && p.status != PlaybackStateType.PLAYING) -> Player.STATE_BUFFERING
                    else -> Player.STATE_READY
                },
            )
            .setContentPositionMs { engine.positionMs() }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) engine.play() else engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> engine.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> engine.previous()
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> engine.seekTo(positionMs)
            else -> {
                val uid = windowUids.getOrNull(mediaItemIndex)
                when {
                    uid == null -> Unit
                    uid == engine.queue.value.current?.uid ->
                        engine.seekTo(if (positionMs == C.TIME_UNSET) 0 else positionMs)
                    else -> engine.playEntry(uid)
                }
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val WINDOW_BEFORE = 100
        const val WINDOW_AFTER = 400

        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
            )
            .build()
    }
}

/** Session/browse representation of a queue entry; mediaId is the entry uid. */
fun QueueEntry.toMediaItem(): MediaItem {
    val track = track
    return MediaItem.Builder()
        .setMediaId(uid.toString())
        .apply { track.url?.let { setUri(it) } }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setArtworkUri(track.imageUrl?.toUri())
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()
}
