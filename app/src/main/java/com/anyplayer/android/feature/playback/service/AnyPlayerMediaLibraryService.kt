package com.anyplayer.android.feature.playback.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import com.anyplayer.android.AnyPlayerApplication
import com.anyplayer.android.core.log.CompatLog
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.common.util.UnstableApi
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import com.anyplayer.android.feature.auth.isSourceConnected
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import com.anyplayer.android.feature.playback.SpotifyConnectBridge
import com.anyplayer.android.feature.playback.trackIdsMatch
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
@UnstableApi
class AnyPlayerMediaLibraryService : MediaLibraryService() {
    @Inject
    lateinit var playerBridge: MediaSessionPlayerBridge
    @Inject
    lateinit var playbackQueueManager: PlaybackQueueManager
    @Inject
    lateinit var authRepository: ProviderAuthRepository
    @Inject
    lateinit var spotifyConnectBridge: SpotifyConnectBridge

    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var restoreJob: Deferred<Unit>? = null
    private lateinit var notificationBuilder: PlaybackNotificationBuilder
    private lateinit var projectionControllerGuard: ProjectionControllerGuard

    override fun onCreate() {
        super.onCreate()
        ensurePlaybackNotificationChannel()
        notificationBuilder = PlaybackNotificationBuilder(this)
        projectionControllerGuard = ProjectionControllerGuard(this, serviceScope, playbackQueueManager)
        playerBridge.open()
        spotifyConnectBridge.attach()
        startProviderRestore()

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            playerBridge,
            object : MediaLibrarySession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    if (!projectionControllerGuard.isTrustedController(controller)) {
                        CompatLog.w(
                            TAG,
                            "Allowing unrecognized media controller package=${controller.packageName} uid=${controller.uid}"
                        )
                    }
                    CompatLog.i(
                        TAG,
                        "Accepted media controller package=${controller.packageName} uid=${controller.uid}"
                    )
                    if (projectionControllerGuard.isProjectionController(controller.packageName)) {
                        projectionControllerGuard.onProjectionControllerConnected(controller)
                    }
                    return super.onConnect(session, controller)
                }

                override fun onDisconnected(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ) {
                    CompatLog.i(
                        TAG,
                        "Controller disconnected package=${controller.packageName} uid=${controller.uid}"
                    )
                    if (projectionControllerGuard.isProjectionController(controller.packageName)) {
                        projectionControllerGuard.onProjectionControllerDisconnected(controller)
                    }
                    super.onDisconnected(session, controller)
                }

                // Must return a valid root so MediaBrowserCompat clients (Android Auto,
                // DHU, Google Home) don't get onConnectionFailed. The default
                // implementation returns RESULT_ERROR_NOT_SUPPORTED which causes all
                // legacy-API clients to disconnect immediately.
                override fun onGetLibraryRoot(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    playbackQueueManager.ensureWarmSessionState()
                    val root = MediaItem.Builder()
                        .setMediaId("root")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setTitle("Any Player")
                                .build()
                        )
                        .build()
                    return Futures.immediateFuture(LibraryResult.ofItem(root, params))
                }

                override fun onGetChildren(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    parentId: String,
                    page: Int,
                    pageSize: Int,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    playbackQueueManager.ensureWarmSessionState()
                    if (parentId != ROOT_ID) {
                        return Futures.immediateFuture(
                            LibraryResult.ofItemList(ImmutableList.of(), params)
                        )
                    }
                    val items = currentDisplayQueue().map { it.toLibraryMediaItem() }
                    return Futures.immediateFuture(
                        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                    )
                }

                override fun onGetItem(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    mediaId: String
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    playbackQueueManager.ensureWarmSessionState()
                    val track = currentDisplayQueue().firstOrNull { it.id == mediaId }
                    val item = track?.toLibraryMediaItem()
                    return if (item != null) {
                        Futures.immediateFuture(LibraryResult.ofItem(item, null))
                    } else {
                        Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                    }
                }

                override fun onSetMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>,
                    startIndex: Int,
                    startPositionMs: Long
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                    serviceScope.launch {
                        try {
                            withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
                                startProviderRestore().await()
                            } ?: CompatLog.w(TAG, "Provider session restore timed out in onSetMediaItems; proceeding")

                            playbackQueueManager.ensureWarmSessionState()

                            val displayQueue = currentDisplayQueue()
                            if (displayQueue.isNotEmpty()) {
                                val requestedIds = mediaItems.mapNotNull { it.mediaId.takeIf(String::isNotBlank) }
                                if (requestedIds.isNotEmpty()) {
                                    val selectedQueue = requestedIds
                                        .mapNotNull { requestedId ->
                                            displayQueue.firstOrNull { track -> trackIdsMatch(track.id, requestedId) }
                                        }
                                        .distinctBy { it.id }
                                        .ifEmpty { displayQueue }
                                    val requestedStartId = requestedIds.getOrNull(startIndex.coerceAtLeast(0))
                                    val mappedStartIndex = requestedStartId
                                        ?.let { id -> selectedQueue.indexOfFirst { trackIdsMatch(it.id, id) } }
                                        ?.takeIf { it >= 0 }
                                        ?: 0

                                    val currentStatus = playbackQueueManager.status.value
                                    val currentQueueIds = currentStatus.queue.map { it.id }
                                    val selectedQueueIds = selectedQueue.map { it.id }
                                    val queueAlreadyLoaded = currentQueueIds.isNotEmpty() &&
                                        currentQueueIds.toSet() == selectedQueueIds.toSet()

                                    if (queueAlreadyLoaded) {
                                        val currentIndex = currentStatus.currentTrack
                                            ?.let { ct -> selectedQueue.indexOfFirst { trackIdsMatch(it.id, ct.id) } }
                                            ?.takeIf { it >= 0 }
                                            ?: 0
                                        val resolvedMediaItems = selectedQueue.map { it.toLibraryMediaItem() }
                                        future.set(
                                            MediaSession.MediaItemsWithStartPosition(
                                                resolvedMediaItems,
                                                currentIndex,
                                                currentStatus.position.coerceAtLeast(0L)
                                            )
                                        )
                                        return@launch
                                    }

                                    playbackQueueManager.setQueue(selectedQueue, mappedStartIndex, autoPlay = true)
                                    if (startPositionMs > 0L) {
                                        playbackQueueManager.seekTo(startPositionMs)
                                    }
                                    val resolvedMediaItems = selectedQueue.map { it.toLibraryMediaItem() }
                                    future.set(
                                        MediaSession.MediaItemsWithStartPosition(
                                            resolvedMediaItems,
                                            mappedStartIndex,
                                            startPositionMs.coerceAtLeast(0L)
                                        )
                                    )
                                    return@launch
                                }

                                val safeStartIndex = startIndex.coerceIn(0, displayQueue.lastIndex)
                                playbackQueueManager.playFromIndex(safeStartIndex)
                                if (startPositionMs > 0L) {
                                    playbackQueueManager.seekTo(startPositionMs)
                                }
                                val resolvedMediaItems = displayQueue.map { it.toLibraryMediaItem() }
                                future.set(
                                    MediaSession.MediaItemsWithStartPosition(
                                        resolvedMediaItems,
                                        safeStartIndex,
                                        startPositionMs.coerceAtLeast(0L)
                                    )
                                )
                                return@launch
                            }

                            future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                        } catch (e: CancellationException) {
                            future.cancel(false)
                            throw e
                        } catch (t: Throwable) {
                                    CompatLog.e(TAG, "Failed to process media items during queue restoration", t)
                            future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                        }
                    }.also { job ->
                        job.invokeOnCompletion { cause ->
                            if (cause is CancellationException && !future.isDone) {
                                future.cancel(false)
                            }
                        }
                    }
                    return future
                }
            }
        ).build()

        serviceScope.launch {
            data class NotificationKey(val trackId: String?, val title: String?, val artist: String?, val state: PlaybackStateType)
            playbackQueueManager.status
                .map { status ->
                    NotificationKey(
                        trackId = status.currentTrack?.id,
                        title = status.currentTrack?.title,
                        artist = status.currentTrack?.artist,
                        state = status.state
                    ) to status
                }
                .distinctUntilChanged { old, new -> old.first == new.first }
                .collect { (_, status) ->
                    startForegroundCompat(notificationBuilder.build(status, mediaLibrarySession))
                }
        }

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        CompatLog.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            PlaybackNotificationBuilder.ACTION_PLAY_PAUSE -> handlePlayPauseWithGuard()
            PlaybackNotificationBuilder.ACTION_NEXT -> playbackQueueManager.next()
            PlaybackNotificationBuilder.ACTION_PREVIOUS -> playbackQueueManager.previous()
        }
        return START_STICKY
    }

    // Prevent Media3's DefaultMediaNotificationProvider from calling stopForeground()
    // during BUFFERING or track transitions — our status coroutine owns the lifecycle.
    override fun onUpdateNotification(session: MediaSession, startInForeground: Boolean) = Unit

    // Keep the service alive when the user removes the app from recents during playback.
    // MediaSessionService's default impl calls stopSelf() if getPlayWhenReady() is false,
    // which hits mid-buffer transitions even while the user intends audio to continue.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (playbackQueueManager.status.value.state == PlaybackStateType.IDLE) {
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        if (!projectionControllerGuard.isTrustedController(controllerInfo)) {
            CompatLog.w(
                TAG,
                "Allowing unrecognized session request package=${controllerInfo.packageName} uid=${controllerInfo.uid}"
            )
        }
        CompatLog.i(
            TAG,
            "onGetSession accepted package=${controllerInfo.packageName} uid=${controllerInfo.uid}"
        )
        return mediaLibrarySession
    }

    override fun onDestroy() {
        serviceScope.cancel()
        playerBridge.close()
        spotifyConnectBridge.release()
        projectionControllerGuard.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        mediaLibrarySession?.run {
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AnyPlayerMediaService"
        private const val ROOT_ID = "root"
        private const val RESTORE_TIMEOUT_MS = 5_000L
    }

    private fun startProviderRestore(): Deferred<Unit> {
        return restoreJob ?: serviceScope.async<Unit> {
            try {
                authRepository.restoreAll()
                playbackQueueManager.signalProviderRestoreComplete()
                playbackQueueManager.restorePersistedStateNowIfNeeded()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                CompatLog.e(TAG, "Failed to restore provider state", t)
                // Signal the gate even on failure so the queue manager doesn't hang
                // waiting forever — it will simply restore without a Spotify session.
                playbackQueueManager.signalProviderRestoreComplete()
            }
        }.also { restoreJob = it }
    }

    private fun currentDisplayQueue(): List<Track> {
        val status = playbackQueueManager.status.value
        return status.orderedQueue.ifEmpty { status.queue }
    }

    private fun Track.toLibraryMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(url ?: "any-player://local/$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(resolvePlaybackArtworkUri())
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    private fun ensurePlaybackNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AnyPlayerApplication.PLAYBACK_CHANNEL_ID,
                "Any Player Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    PlaybackNotificationBuilder.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(PlaybackNotificationBuilder.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            CompatLog.e(TAG, "startForeground failed: ${e.message}", e)
            stopSelf()
        }
    }

    private fun handlePlayPauseWithGuard() {
        serviceScope.launch {
            val status = playbackQueueManager.status.value
            val connected = authRepository.isSourceConnected(status.currentTrack?.source)
            // Re-read status after the suspend call to detect track changes during auth check
            val freshStatus = playbackQueueManager.status.value
            if (connected) {
                if (freshStatus.currentTrack?.id == status.currentTrack?.id) {
                    playbackQueueManager.togglePlayPause()
                } else {
            CompatLog.d(TAG, "Skipping play/pause: track changed during provider auth check")
                }
                return@launch
            }

            if (freshStatus.state == PlaybackStateType.PLAYING) {
                playbackQueueManager.pause()
            }
            CompatLog.w(TAG, "Blocked play/pause from notification: current track provider is not configured/authenticated")
        }
    }

}
