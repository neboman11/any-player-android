package com.anyplayer.android.playback.backend

import android.content.Context
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.anyplayer.android.core.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One native gapless timeline, updated after the current item without rebuilding its source. */
@Singleton
class ExoBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cache: AudioCache,
) : PlaybackBackend {
    override val kind = BackendKind.EXO
    private val mutableEvents = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events = mutableEvents.asSharedFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    private var player: ExoPlayer? = null
    private var loadId = 0L
    private var byPlayer = false
    private var changing = false
    private var volume = 100
    private var repeatOne = false
    private var retries = 0
    private var retryKey: ItemKey? = null
    private var retryJob: Job? = null
    private var prefetchJob: Job? = null
    private var prefetchedUid: Long? = null
    private var run: Run? = null

    private fun emitPlaying() {
        val p = player ?: return
        if (!changing) mutableEvents.tryEmit(BackendEvent.PlayingChanged(loadId, p.isPlaying,
            p.playbackState == Player.STATE_BUFFERING && p.playWhenReady, byPlayer))
    }
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (changing) return
            val key = mediaItem?.let { key(it.mediaId) } ?: return
            if (retryKey != key) { retryKey = key; retries = 0; retryJob?.cancel() }
            mutableEvents.tryEmit(BackendEvent.ItemStarted(loadId, key))
            prefetch()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) = emitPlaying()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            byPlayer = !playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            emitPlaying()
        }
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                byPlayer = true
                player?.pause()
                emitPlaying()
            }
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (changing) return
            emitPlaying()
            if (playbackState == Player.STATE_ENDED) mutableEvents.tryEmit(BackendEvent.RunEnded(loadId))
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (!changing) mutableEvents.tryEmit(BackendEvent.PositionDiscontinuity(loadId, newPosition.positionMs, durationMs()))
        }
        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            val id = loadId
            val item = p.currentMediaItem?.let { key(it.mediaId) }
            if (item != retryKey) { retryKey = item; retries = 0 }
            retryJob?.cancel()
            if (retries < 3) {
                retries++
                retryJob = scope.launch {
                    delay(1500)
                    commands.withLock { if (loadId == id && player?.currentMediaItem?.let { key(it.mediaId) } == item) p.prepare() }
                }
            } else mutableEvents.tryEmit(BackendEvent.Error(id, item, error.message ?: error.errorCodeName))
        }
    }
    private suspend fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val factory = cache.factory()
        return ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build().also {
                it.volume = volume / 100f
                it.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                it.addListener(listener); player = it
            }
    }
    override suspend fun load(loadId: Long, run: Run, startIndex: Int, positionMs: Long, playWhenReady: Boolean) = commands.withLock {
        val p = ensurePlayer()
        retryJob?.cancel(); retries = 0
        changing = true
        this.loadId = loadId; this.run = run; byPlayer = false
        try {
            val target = run.items[startIndex].key()
            val existing = (0 until p.mediaItemCount).firstOrNull { key(p.getMediaItemAt(it).mediaId) == target }
            if (existing != null) {
                p.seekTo(existing, positionMs)
                updateTimeline(run, target)
            } else p.setMediaItems(run.items.map(::toMediaItem), startIndex, positionMs)
            p.playWhenReady = playWhenReady
            p.prepare()
        } finally { changing = false }
        p.currentMediaItem?.let { key(it.mediaId) }?.let { retryKey = it; mutableEvents.tryEmit(BackendEvent.ItemStarted(loadId, it)) }
        emitPlaying(); prefetch()
    }
    override suspend fun updateUpcoming(loadId: Long, run: Run, currentKey: ItemKey) = commands.withLock {
        if (loadId == this.loadId && player?.currentMediaItem?.let { key(it.mediaId) } == currentKey) {
            this.run = run; updateTimeline(run, currentKey); prefetch()
        }
    }
    private fun updateTimeline(run: Run, currentKey: ItemKey) {
        val p = player ?: return
        val index = run.items.indexOfFirst { it.key() == currentKey }
        if (index < 0) return
        val desired = run.items.drop(index + 1)
        val start = p.currentMediaItemIndex + 1
        var same = 0
        while (same < desired.size && start + same < p.mediaItemCount && key(p.getMediaItemAt(start + same).mediaId) == desired[same].key()) same++
        if (start + same < p.mediaItemCount) p.removeMediaItems(start + same, p.mediaItemCount)
        if (same < desired.size) p.addMediaItems(desired.drop(same).map(::toMediaItem))
        val trim = (p.currentMediaItemIndex - 3).coerceAtLeast(0)
        if (trim > 0) p.removeMediaItems(0, trim)
    }
    private fun prefetch() {
        val p = player ?: return
        val index = run?.items?.indexOfFirst { it.key() == p.currentMediaItem?.let { m -> key(m.mediaId) } } ?: -1
        val next = run?.items?.drop(index + 1)?.filterIsInstance<PlanItem.Song>()?.firstOrNull()
        if (next?.entry?.uid == prefetchedUid) return
        prefetchJob?.cancel(); prefetchedUid = next?.entry?.uid
        prefetchJob = next?.let { scope.launch { cache.prefetch(it.entry.track) } }
    }
    override suspend fun seekToItem(key: ItemKey, positionMs: Long) = commands.withLock {
        val p = player ?: return@withLock
        val index = (0 until p.mediaItemCount).firstOrNull { key(p.getMediaItemAt(it).mediaId) == key } ?: return@withLock
        p.seekTo(index, positionMs)
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) p.prepare()
    }
    override suspend fun play() = commands.withLock { player?.let { byPlayer = false; if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() }; Unit }
    override suspend fun pause() = commands.withLock { byPlayer = false; player?.pause(); Unit }
    override suspend fun seekTo(ms: Long) = commands.withLock { player?.seekTo(ms); Unit }
    override suspend fun setVolume(effectivePercent: Int) = commands.withLock { volume = effectivePercent; player?.volume = volume / 100f; Unit }
    override suspend fun setRepeatOne(on: Boolean) = commands.withLock { repeatOne = on; player?.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF; Unit }
    override suspend fun stop() = commands.withLock {
        retryJob?.cancel(); prefetchJob?.cancel(); prefetchedUid = null
        changing = true
        try { player?.stop(); player?.clearMediaItems(); run = null } finally { changing = false }
    }
    override fun positionMs(): Long = player?.currentPosition ?: 0
    override fun durationMs(): Long = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0
    private fun key(id: String): ItemKey? = if (id.startsWith("dj-filler:")) ItemKey.Break(id.removePrefix("dj-filler:")) else id.toLongOrNull()?.let(ItemKey::Song)
    private fun toMediaItem(item: PlanItem): MediaItem = when (item) {
        is PlanItem.Song -> MediaItem.Builder().setMediaId(item.entry.uid.toString()).setUri(item.entry.track.url)
            .setCustomCacheKey("${item.entry.track.source}:${item.entry.track.id}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.entry.track.title).setArtist(item.entry.track.artist).setAlbumTitle(item.entry.track.album).build()).build()
        is PlanItem.Break -> MediaItem.Builder().setMediaId("dj-filler:${item.breakId}").setUri(item.audioUri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle("AnyPlayer DJ").build()).build()
    }
}

internal fun PlanItem.key(): ItemKey = when (this) {
    is PlanItem.Song -> ItemKey.Song(entry.uid)
    is PlanItem.Break -> ItemKey.Break(breakId)
}
