package com.anyplayer.android.feature.playback.service

import android.app.PendingIntent
import android.content.*
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.anyplayer.android.core.model.*
import com.anyplayer.android.playback.PlaybackEngine
import com.anyplayer.android.playback.PlaybackNotifications
import com.anyplayer.android.playback.R
import com.anyplayer.android.playback.session.SessionPlayer
import com.anyplayer.android.playback.session.toMediaItem
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class AnyPlayerMediaLibraryService : MediaLibraryService() {
    @Inject lateinit var engine: PlaybackEngine
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val projection = HashSet<MediaSession.ControllerInfo>()
    private var projectionPause: Job? = null
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                engine.queue.value.current?.track?.source == SourceType.SPOTIFY && engine.player.value.playRequested) engine.pause()
        }
    }
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(PlaybackNotifications.CHANNEL_ID).setChannelName(R.string.playback_channel_name)
            .setNotificationId(1001).build().apply { setSmallIcon(R.drawable.ic_notification) })
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
                val pending = PendingIntent.getActivity(this@AnyPlayerMediaLibraryService, 0, launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val notification = NotificationCompat.Builder(this@AnyPlayerMediaLibraryService, PlaybackNotifications.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle("Any Player")
                    .setContentText("Tap to resume playback control").setContentIntent(pending).setAutoCancel(true).build()
                try { NotificationManagerCompat.from(this@AnyPlayerMediaLibraryService).notify(1001, notification) }
                catch (_: SecurityException) { /* Notification permission may be declined. */ }
            }
        })
        val builder = MediaLibrarySession.Builder(this, SessionPlayer(engine), LibraryCallback())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        session = builder.build().also(::addSession)
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onDestroy() {
        unregisterReceiver(noisy); scope.cancel()
        session?.run { player.release(); release() }; session = null
        super.onDestroy()
    }
    private fun isProjection(controller: MediaSession.ControllerInfo) = PROJECTION_PREFIXES.any(controller.packageName::startsWith)
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = scope.launch { try { future.set(block()) } catch (e: Exception) { future.setException(e) } }
        future.addListener({ if (future.isCancelled) job.cancel() }, java.util.concurrent.Executor { it.run() })
        return future
    }
    private fun queueItems(): List<MediaItem> { val q = engine.queue.value; return q.order.map { q.entries[it].toMediaItem() } }
    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (isProjection(controller)) { projection.add(controller); projectionPause?.cancel() }
            return super.onConnect(session, controller)
        }
        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            super.onDisconnected(session, controller)
            if (projection.remove(controller) && projection.isEmpty()) {
                projectionPause?.cancel()
                projectionPause = scope.launch { delay(1500); if (projection.isEmpty() && engine.player.value.playRequested) engine.pause() }
            }
        }
        override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            withTimeoutOrNull(5000) { engine.awaitRestored() }
            val q = engine.queue.value
            MediaSession.MediaItemsWithStartPosition(queueItems(), q.cursor.coerceAtLeast(0), engine.positionMs())
        }
        override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            withTimeoutOrNull(5000) { engine.awaitRestored() }
            val q = engine.queue.value
            val matches = mediaItems.mapNotNull { requested -> q.entries.firstOrNull { it.uid.toString() == requested.mediaId }
                ?: q.entries.firstOrNull { trackIdsMatch(it.track.id, requested.mediaId) } }
            val same = matches.isNotEmpty() && matches.map { it.uid }.toSet() == q.entries.map { it.uid }.toSet()
            if (same || matches.isEmpty()) MediaSession.MediaItemsWithStartPosition(queueItems(), q.cursor.coerceAtLeast(0), engine.positionMs())
            else MediaSession.MediaItemsWithStartPosition(matches.map { it.toMediaItem() }, startIndex.coerceIn(matches.indices), startPositionMs)
        }
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(MediaItem.Builder().setMediaId("root")
                .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).setTitle("Any Player").build()).build(), params))
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val from = (page.toLong() * pageSize).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            val items = if (parentId == "root" && page >= 0 && pageSize > 0) queueItems().drop(from).take(pageSize) else emptyList()
            return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
        }
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val q = engine.queue.value
            val entry = q.entries.firstOrNull { it.uid.toString() == mediaId } ?: q.entries.firstOrNull { trackIdsMatch(it.track.id, mediaId) }
            return Futures.immediateFuture(entry?.let { LibraryResult.ofItem(it.toMediaItem(), null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }
    }
    companion object {
        private val PROJECTION_PREFIXES = listOf("com.google.android.projection.gearhead", "com.google.android.apps.auto", "com.android.car")
    }
}
