package com.anyplayer.android.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.anyplayer.android.core.model.PlaybackStateType
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
 android-companion-implementation-spec.md app build build.gradle.kts docs gradle gradle.properties gradlew gradlew.bat local.properties settings.gradle.kts    state changes never reach the session layer. All events come from the
 android-companion-implementation-spec.md app build build.gradle.kts docs gradle gradle.properties gradlew gradlew.bat local.properties settings.gradle.kts    PlaybackQueueManager StateFlow instead.
 */
@Singleton
class MediaSessionPlayerBridge @Inject constructor(
    private val media3PlaybackController: Media3PlaybackController,
    private val playbackQueueManager: PlaybackQueueManager
) : ForwardingPlayer(media3PlaybackController.player) {

    private val listeners = CopyOnWriteArrayList<Player.Listener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch {
            var prev = playbackQueueManager.status.value
            Log.d(TAG, "StateFlow collector started; initial state=${prev.state} track=${prev.currentTrack?.id}")
            playbackQueueManager.status.collect { status ->
                if (status === prev) return@collect
                val prevSnap = prev
                prev = status
                Log.d(TAG, "StateFlow emitted: ${prevSnap.state}->${status.state} track=${status.currentTrack?.id} listeners=${listeners.size}")

                val newState    = mapState(status.state)
                val prevState   = mapState(prevSnap.state)
                val nowPlaying  = status.state == PlaybackStateType.PLAYING
                val wasPlaying  = prevSnap.state == PlaybackStateType.PLAYING
                val trackChanged    = status.currentTrack?.id != prevSnap.currentTrack?.id
                val commandsChanged = (status.currentTrack != null) != (prevSnap.currentTrack != null)
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
                    if (commandsChanged) l.onAvailableCommandsChanged(buildCommands(status.currentTrack != null))
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
        val status   = playbackQueueManager.status.value
        val hasTrack = status.currentTrack != null
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
            FlagSet.Builder()
                .addAll(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED
                ).build()
        )
        listener.onEvents(this, bootstrapEvents)
    }
    override fun removeListener(listener: Player.Listener) {
        Log.d(TAG, "removeListener: ${listener.javaClass.simpleName}")
        listeners.remove(listener)
    }

    // State — always from PlaybackQueueManager
    override fun getPlaybackState(): Int = mapState(playbackQueueManager.status.value.state)
    override fun isPlaying(): Boolean = playbackQueueManager.status.value.state == PlaybackStateType.PLAYING
    override fun getPlayWhenReady(): Boolean = isPlaying()

    override fun getCurrentMediaItem(): MediaItem? =
        playbackQueueManager.status.value.currentTrack?.toMediaItem()

    override fun getMediaMetadata(): MediaMetadata =
        getCurrentMediaItem()?.mediaMetadata ?: MediaMetadata.EMPTY

    override fun getDuration(): Long {
        val d = playbackQueueManager.status.value.duration
        return if (d > 0L) d else C.TIME_UNSET
    }

    override fun getCurrentPosition(): Long  = playbackQueueManager.status.value.position
    override fun getBufferedPosition(): Long = playbackQueueManager.status.value.position

    override fun getMediaItemCount(): Int =
        playbackQueueManager.status.value.queue.size
            .coerceAtLeast(if (getCurrentMediaItem() != null) 1 else 0)

    override fun getCurrentMediaItemIndex(): Int {
        val s  = playbackQueueManager.status.value
        val id = s.currentTrack?.id ?: return 0
        val i  = s.queue.indexOfFirst { it.id == id }
        return if (i >= 0) i else 0
    }

    override fun getAvailableCommands(): Player.Commands =
        buildCommands(playbackQueueManager.status.value.currentTrack != null)

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
        val s = playbackQueueManager.status.value
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
