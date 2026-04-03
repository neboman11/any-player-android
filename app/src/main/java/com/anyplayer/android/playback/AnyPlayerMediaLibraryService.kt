package com.anyplayer.android.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.common.util.UnstableApi
import com.anyplayer.android.AnyPlayerApplication
import com.anyplayer.android.MainActivity
import com.anyplayer.android.R
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import com.anyplayer.android.feature.auth.isSourceConnected
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
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

    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var activeProjectionControllers = 0
    private var projectionDisconnectPauseJob: Job? = null
    private var wasPlayingBeforeFocusLoss = false
    private var restoreJob: Deferred<Unit>? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val activeSource = playbackQueueManager.status.value.currentTrack?.source
        if (activeSource != SourceType.SPOTIFY) {
            wasPlayingBeforeFocusLoss = false
            return@OnAudioFocusChangeListener
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                val shouldResume = wasPlayingBeforeFocusLoss
                wasPlayingBeforeFocusLoss = false
                if (shouldResume &&
                    playbackQueueManager.status.value.state == PlaybackStateType.PAUSED
                ) {
                    playbackQueueManager.play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                wasPlayingBeforeFocusLoss = false
                playbackQueueManager.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val isPlaying = playbackQueueManager.status.value.state == PlaybackStateType.PLAYING
                wasPlayingBeforeFocusLoss = isPlaying
                if (isPlaying) {
                    playbackQueueManager.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Let the system handle ducking via AudioAttributes; no manual volume change needed.
                // Media3/ExoPlayer handles ducking automatically when AudioAttributes are set.
            }
        }
    }
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
        startProviderRestore()

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            playerBridge,
            object : MediaLibrarySession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    if (!isTrustedController(controller)) {
                        Log.w(
                            TAG,
                            "Allowing unrecognized media controller package=${controller.packageName} uid=${controller.uid}"
                        )
                    }
                    Log.i(
                        TAG,
                        "Accepted media controller package=${controller.packageName} uid=${controller.uid}"
                    )
                    if (isProjectionController(controller.packageName)) {
                        onProjectionControllerConnected(controller)
                    }
                    return super.onConnect(session, controller)
                }

                override fun onDisconnected(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ) {
                    Log.i(
                        TAG,
                        "Controller disconnected package=${controller.packageName} uid=${controller.uid}"
                    )
                    if (isProjectionController(controller.packageName)) {
                        onProjectionControllerDisconnected(controller)
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
                            } ?: Log.w(TAG, "Provider session restore timed out in onSetMediaItems; proceeding")

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
                            Log.w(TAG, "Failed to process media items during queue restoration", t)
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
            playbackQueueManager.status.collect { status ->
                updateAudioFocus(status)
                startForegroundCompat(buildPlaybackNotification(status))
            }
        }

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> handlePlayPauseWithGuard()
            ACTION_NEXT -> playbackQueueManager.next()
            ACTION_PREVIOUS -> playbackQueueManager.previous()
        }
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        if (!isTrustedController(controllerInfo)) {
            Log.w(
                TAG,
                "Allowing unrecognized session request package=${controllerInfo.packageName} uid=${controllerInfo.uid}"
            )
        }
        Log.i(
            TAG,
            "onGetSession accepted package=${controllerInfo.packageName} uid=${controllerInfo.uid}"
        )
        return mediaLibrarySession
    }

    override fun onDestroy() {
        serviceScope.cancel()
        projectionDisconnectPauseJob?.cancel()
        projectionDisconnectPauseJob = null
        activeProjectionControllers = 0
        abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        mediaLibrarySession?.run {
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AnyPlayerMediaService"
        private const val NOTIFICATION_ID = 1001
        private const val ROOT_ID = "root"
        private const val ACTION_PLAY_PAUSE = "com.anyplayer.android.action.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.anyplayer.android.action.NEXT"
        private const val ACTION_PREVIOUS = "com.anyplayer.android.action.PREVIOUS"
        private const val PROJECTION_DISCONNECT_GRACE_MS = 1500L
        private const val RESTORE_TIMEOUT_MS = 5_000L
        private val TRUSTED_CONTROLLER_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.android.car.media",
            "com.google.android.apps.automotive.media"
        )
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
                Log.w(TAG, "Failed to restore provider state", t)
                // Signal the gate even on failure so the queue manager doesn't hang
                // waiting forever — it will simply restore without a Spotify session.
                playbackQueueManager.signalProviderRestoreComplete()
            }
        }.also { restoreJob = it }
    }

    private fun isTrustedController(controllerInfo: MediaSession.ControllerInfo): Boolean {
        val controllerPackage = controllerInfo.packageName
        if (controllerPackage == packageName) return true
        if (controllerInfo.uid == Process.SYSTEM_UID) return true
        if (controllerPackage in TRUSTED_CONTROLLER_PACKAGES) return true
        return isSystemApp(controllerPackage)
    }

    private fun isSystemApp(controllerPackage: String): Boolean {
        val appInfo = runCatching {
            packageManager.getApplicationInfo(controllerPackage, 0)
        }.getOrNull() ?: return false
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return (appInfo.flags and systemFlags) != 0
    }

    private fun isProjectionController(controllerPackage: String): Boolean {
        if (controllerPackage == "com.google.android.projection.gearhead") return true
        if (controllerPackage.startsWith("com.google.android.apps.auto")) return true
        if (controllerPackage.startsWith("com.android.car")) return true
        return false
    }

    private fun onProjectionControllerConnected(controller: MediaSession.ControllerInfo) {
        activeProjectionControllers += 1
        projectionDisconnectPauseJob?.cancel()
        projectionDisconnectPauseJob = null
        Log.i(
            TAG,
            "Projection controller connected package=${controller.packageName} active=$activeProjectionControllers"
        )
    }

    private fun onProjectionControllerDisconnected(controller: MediaSession.ControllerInfo) {
        activeProjectionControllers = (activeProjectionControllers - 1).coerceAtLeast(0)
        Log.i(
            TAG,
            "Projection controller disconnected package=${controller.packageName} active=$activeProjectionControllers"
        )
        if (activeProjectionControllers > 0) return
        projectionDisconnectPauseJob?.cancel()
        projectionDisconnectPauseJob = serviceScope.launch {
            delay(PROJECTION_DISCONNECT_GRACE_MS)
            val current = playbackQueueManager.status.value
            if (activeProjectionControllers == 0 && current.state == PlaybackStateType.PLAYING) {
                Log.i(TAG, "Projection controllers inactive after grace period; pausing playback")
                playbackQueueManager.pause()
            }
        }
    }

    private fun currentDisplayQueue(): List<Track> {
        val status = playbackQueueManager.status.value
        return status.orderedQueue.ifEmpty { status.queue }
    }

    private fun trackIdsMatch(left: String, right: String): Boolean {
        if (left == right) return true
        return normalizeSpotifyTrackId(left) == normalizeSpotifyTrackId(right)
    }

    private fun normalizeSpotifyTrackId(value: String): String =
        value.removePrefix("spotify:track:").trim()

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

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildPlaybackNotification(status: PlaybackStatus): Notification {
        val currentTrack = status.currentTrack
        val hasTrack = currentTrack != null
        val isPlaying = status.state == PlaybackStateType.PLAYING
        val mediaStyle = MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
        mediaLibrarySession?.sessionCompatToken?.let { mediaStyle.setMediaSession(it) }
        return NotificationCompat.Builder(this, AnyPlayerApplication.PLAYBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(currentTrack?.title ?: getString(R.string.app_name))
            .setContentText(currentTrack?.artist ?: "Ready")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setStyle(mediaStyle)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(hasTrack && isPlaying)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                actionPendingIntent(ACTION_PREVIOUS)
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                actionPendingIntent(ACTION_PLAY_PAUSE)
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                actionPendingIntent(ACTION_NEXT)
            )
            .build()
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AnyPlayerMediaLibraryService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
                    Log.d(TAG, "Skipping play/pause: track changed during provider auth check")
                }
                return@launch
            }

            if (freshStatus.state == PlaybackStateType.PLAYING) {
                playbackQueueManager.pause()
            }
            Log.w(TAG, "Blocked play/pause from notification: current track provider is not configured/authenticated")
        }
    }

    private fun updateAudioFocus(status: PlaybackStatus) {
        val currentTrack = status.currentTrack

        // Only manage audio focus here for sources that do NOT rely on ExoPlayer's
        // built-in audio focus handling (e.g., the Rust/Spotify path). Media3/ExoPlayer
        // playback already handles audio focus via setAudioAttributes(..., handleAudioFocus = true),
        // so we avoid double pause/resume handling by skipping those sources here.
        if (currentTrack != null && currentTrack.source != SourceType.SPOTIFY) {
            // We may still be holding audio focus from a previous Spotify track.
            // Explicitly abandon it before deferring to ExoPlayer's own focus handling.
            abandonAudioFocus()
            wasPlayingBeforeFocusLoss = false
            return
        }

        if (status.state == PlaybackStateType.PLAYING && currentTrack != null) {
            requestAudioFocus()
        } else {
            abandonAudioFocus()
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager?.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusListener)
        }
    }
}
