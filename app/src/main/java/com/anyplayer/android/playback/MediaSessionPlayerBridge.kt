package com.anyplayer.android.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.playback.Media3PlaybackController
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ForwardingPlayer wrapping ExoPlayer, with listener isolation.
 *
 * Why ForwardingPlayer (not a plain Player stub):
 *   Android Auto routes audio through the Player backing the MediaLibrarySession.
 *   A plain stub produces no audio; ExoPlayer must remain the wrapped player so
 *   Media3 wires up AudioFocus and the car audio output correctly.
 *
 * Why we block super.addListener:
 *   Passing listeners to ExoPlayer causes it to fire STATE_IDLE events (ExoPlayer
 *   has no media loaded when Spotify is active). DefaultMediaNotificationProvider
 *   receives those, stops the foreground service, kills the notification, and Auto
 *   grays out controls. By owning the listener list ourselves, ExoPlayer's internal
 *   state changes never reach the session layer. All events come from the
 *   PlaybackQueueManager StateFlow instead.
 */
@Singleton
@UnstableApi
class MediaSessionPlayerBridge @Inject constructor(
    private val media3PlaybackController: Media3PlaybackController,
    private val playbackQueueManager: PlaybackQueueManager
) : ForwardingPlayer(media3PlaybackController.player) {

    private val listeners = CopyOnWriteArrayList<Player.Listener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile
    private var currentStatusSnapshot: PlaybackStatus = playbackQueueManager.status.value

    init {
        scope.launch {
            var prev = currentStatusSnapshot
            Log.d(TAG, "StateFlow collector started; initial state=${prev.state} track=${prev.currentTrack?.id}")
            playbackQueueManager.status.collect { status ->
                if (status === prev) return@collect
                val prevSnap = prev
                prev = status
                currentStatusSnapshot = status
                Log.d(TAG, "StateFlow emitted: ${prevSnap.state}->${status.state} track=${status.currentTrack?.id} listeners=${listeners.size}")

                val newState    = mapState(status.state)
                val prevState   = mapState(prevSnap.state)
                val nowPlaying  = status.state == PlaybackStateType.PLAYING
                val wasPlaying  = prevSnap.state == PlaybackStateType.PLAYING
                val trackChanged    = status.currentTrack?.id != prevSnap.currentTrack?.id
                val hasItemsNow = status.currentTrack != null || status.queue.isNotEmpty()
                val hadItemsBefore = prevSnap.currentTrack != null || prevSnap.queue.isNotEmpty()
                val commandsChanged = hasItemsNow != hadItemsBefore
                val timelineChanged = status.queue.map { it.id } != prevSnap.queue.map { it.id }
                val metadataChanged = trackChanged

                // Build the events bitmask for onEvents — DefaultMediaNotificationProvider
                // and MediaLibrarySession internals use onEvents, not individual callbacks.
                val eventBits = FlagSet.Builder()
                if (newState != prevState)    eventBits.add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                if (nowPlaying != wasPlaying) eventBits.add(Player.EVENT_IS_PLAYING_CHANGED)
                if (trackChanged)             eventBits.add(Player.EVENT_MEDIA_ITEM_TRANSITION)
                if (metadataChanged)          eventBits.add(Player.EVENT_MEDIA_METADATA_CHANGED)
                if (timelineChanged)          eventBits.add(Player.EVENT_TIMELINE_CHANGED)
                if (commandsChanged)          eventBits.add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
                val playerEvents = Player.Events(eventBits.build())

                Log.d(TAG, "Firing events: stateCh=${ newState != prevState} playingCh=${nowPlaying != wasPlaying} trackCh=$trackChanged cmdCh=$commandsChanged")
                listeners.forEach { l ->
                    if (newState != prevState)    l.onPlaybackStateChanged(newState)
                    if (nowPlaying != wasPlaying) l.onIsPlayingChanged(nowPlaying)
                    if (trackChanged) {
                        l.onMediaItemTransition(
                            status.currentTrack?.toMediaItem(),
                            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                        )
                    }
                    if (metadataChanged) l.onMediaMetadataChanged(getMediaMetadata())
                    if (commandsChanged) l.onAvailableCommandsChanged(buildCommands(hasItemsNow))
                    l.onEvents(this@MediaSessionPlayerBridge, playerEvents)
                }
            }
        }
    }

    // Listener management — do NOT forward to ExoPlayer.
    // Bootstrap each new listener with the current state immediately so the session
    // and DefaultMediaNotificationProvider are in sync from the moment they register.
    override fun addListener(listener: Player.Listener) {
        Log.d(TAG, "addListener: ${listener.javaClass.simpleName} (total after=${listeners.size + 1})")
        listeners.add(listener)
        val status   = currentStatus()
        val hasTrack = status.currentTrack != null || status.queue.isNotEmpty()
        val state    = mapState(status.state)
        val playing  = status.state == PlaybackStateType.PLAYING
        Log.d(TAG, "bootstrap -> state=$state playing=$playing track=${status.currentTrack?.id} hasTrack=$hasTrack")
        listener.onPlaybackStateChanged(state)
        listener.onIsPlayingChanged(playing)
        if (hasTrack) listener.onMediaItemTransition(
            status.currentTrack?.toMediaItem(),
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        )
        listener.onMediaMetadataChanged(getMediaMetadata())
        listener.onAvailableCommandsChanged(buildCommands(hasTrack))
        val bootstrapEvents = Player.Events(
            FlagSet.Builder().apply {
                add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                add(Player.EVENT_IS_PLAYING_CHANGED)
                if (hasTrack) add(Player.EVENT_MEDIA_ITEM_TRANSITION)
                add(Player.EVENT_MEDIA_METADATA_CHANGED)
                add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
                add(Player.EVENT_TIMELINE_CHANGED)
            }.build()
        )
        listener.onEvents(this, bootstrapEvents)
    }
    override fun removeListener(listener: Player.Listener) {
        Log.d(TAG, "removeListener: ${listener.javaClass.simpleName}")
        listeners.remove(listener)
    }

    // State — always from PlaybackQueueManager
    override fun getPlaybackState(): Int = mapState(currentStatus().state)
    override fun isPlaying(): Boolean = currentStatus().state == PlaybackStateType.PLAYING
    override fun getPlayWhenReady(): Boolean = isPlaying()

    override fun getCurrentMediaItem(): MediaItem? =
        currentTrackOrFallback()?.toMediaItem()

    override fun getMediaMetadata(): MediaMetadata =
        getCurrentMediaItem()?.mediaMetadata ?: MediaMetadata.EMPTY

    override fun getCurrentTimeline(): Timeline =
        QueueTimeline(timelineTracks())

    override fun getDuration(): Long {
        val d = currentStatus().duration
        return if (d > 0L) d else C.TIME_UNSET
    }

    override fun getCurrentPosition(): Long  = currentStatus().position
    override fun getBufferedPosition(): Long = currentStatus().position

    override fun getMediaItemCount(): Int =
        timelineTracks().size

    override fun getCurrentMediaItemIndex(): Int {
        val tracks = timelineTracks()
        if (tracks.isEmpty()) return 0
        val s  = currentStatus()
        val id = s.currentTrack?.id ?: return 0
        val i  = tracks.indexOfFirst { it.id == id }
        return if (i >= 0) i else 0
    }

    override fun getCurrentPeriodIndex(): Int = getCurrentMediaItemIndex()

    override fun getNextMediaItemIndex(): Int {
        val count = getMediaItemCount()
        if (count <= 0) return C.INDEX_UNSET
        val next = getCurrentMediaItemIndex() + 1
        return if (next in 0 until count) next else C.INDEX_UNSET
    }

    override fun getPreviousMediaItemIndex(): Int {
        val count = getMediaItemCount()
        if (count <= 0) return C.INDEX_UNSET
        val prev = getCurrentMediaItemIndex() - 1
        return if (prev in 0 until count) prev else C.INDEX_UNSET
    }

    override fun getPlayerError(): PlaybackException? {
        val status = currentStatus()
        if (status.state != PlaybackStateType.ERROR) return null
        val message = status.errorMessage?.takeIf { it.isNotBlank() }
            ?: "Playback failed"
        return PlaybackException(message, null, PlaybackException.ERROR_CODE_UNSPECIFIED)
    }

    override fun getAvailableCommands(): Player.Commands =
        buildCommands(currentStatus().currentTrack != null || currentStatus().queue.isNotEmpty())

    // Transport commands — delegate to PlaybackQueueManager
    override fun play()  { playbackQueueManager.play() }
    override fun pause() { playbackQueueManager.pause() }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }
    override fun seekToNext()              { playbackQueueManager.next() }
    override fun seekToNextMediaItem()     { playbackQueueManager.next() }
    override fun seekToPrevious()          { playbackQueueManager.previous() }
    override fun seekToPreviousMediaItem() { playbackQueueManager.previous() }
    override fun seekTo(positionMs: Long)  { playbackQueueManager.seekTo(positionMs) }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val s = currentStatus()
        val targetId = s.queue.getOrNull(mediaItemIndex)?.id
        if (targetId != null && targetId != s.currentTrack?.id) {
            playbackQueueManager.playFromIndex(mediaItemIndex)
        } else {
            playbackQueueManager.seekTo(positionMs)
        }
    }

    companion object { private const val TAG = "PlayerBridge" }

    private fun mapState(state: PlaybackStateType): Int = when (state) {
        PlaybackStateType.PLAYING,
        PlaybackStateType.PAUSED    -> Player.STATE_READY
        PlaybackStateType.BUFFERING -> Player.STATE_BUFFERING
        PlaybackStateType.IDLE,
        PlaybackStateType.ERROR     -> Player.STATE_IDLE
    }

    private fun buildCommands(hasTrack: Boolean): Player.Commands {
        val b = Player.Commands.Builder().addAll(
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_MEDIA_ITEMS_METADATA,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_GET_VOLUME,
            Player.COMMAND_GET_DEVICE_VOLUME,
        )
        if (hasTrack) b.addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        )
        return b.build()
    }

    private fun currentTrackOrFallback(): Track? {
        val status = currentStatus()
        return status.currentTrack ?: status.queue.firstOrNull()
    }

    private fun timelineTracks(): List<Track> {
        val status = currentStatus()
        if (status.queue.isNotEmpty()) return status.queue
        return status.currentTrack?.let(::listOf).orEmpty()
    }

    private fun currentStatus(): PlaybackStatus = currentStatusSnapshot
}

private fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(url ?: "any-player://local/$id")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(imageUrl?.let(Uri::parse))
                .setIsPlayable(true)
                .build()
        )
        .build()

@UnstableApi
private class QueueTimeline(
    private val tracks: List<Track>
) : Timeline() {
    override fun getWindowCount(): Int = tracks.size

    override fun getPeriodCount(): Int = tracks.size

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        val track = tracks[windowIndex]
        val durationUs = track.durationMs
            ?.takeIf { it > 0 }
            ?.let(C::msToUs)
            ?: C.TIME_UNSET
        return window.set(
            Window.SINGLE_WINDOW_UID,
            track.toMediaItem(),
            null,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            false,
            true,
            null,
            0,
            durationUs,
            0,
            0,
            0
        )
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        val id = tracks[periodIndex].id
        return period.set(
            if (setIds) id else null,
            if (setIds) id else null,
            periodIndex,
            C.TIME_UNSET,
            0
        )
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        val stringUid = uid as? String ?: return C.INDEX_UNSET
        val index = tracks.indexOfFirst { it.id == stringUid }
        return if (index >= 0) index else C.INDEX_UNSET
    }

    override fun getUidOfPeriod(periodIndex: Int): Any = tracks[periodIndex].id
}
