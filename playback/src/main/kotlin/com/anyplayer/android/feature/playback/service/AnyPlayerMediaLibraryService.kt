package com.anyplayer.android.feature.playback.service

import android.app.PendingIntent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.anyplayer.android.playback.PlaybackEngine
import com.anyplayer.android.playback.PlaybackNotifications
import com.anyplayer.android.playback.R
import com.anyplayer.android.playback.session.SessionPlayer
import com.anyplayer.android.playback.session.toMediaItem
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Media3 library service (FQN kept from the legacy app). The session player is derived from the
 * engine; Media3's [DefaultMediaNotificationProvider] owns the notification.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class AnyPlayerMediaLibraryService : MediaLibraryService() {

    @Inject lateinit var engine: PlaybackEngine

    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(PlaybackNotifications.CHANNEL_ID)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )
        val builder = MediaLibrarySession.Builder(this, SessionPlayer(engine), LibraryCallback())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        // The engine starts this service with startService (no controller binds), so register
        // the session explicitly; otherwise Media3 never posts the notification / foregrounds.
        session = builder.build().also(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle("Any Player")
                        .build(),
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
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = if (parentId == ROOT_ID) {
                val q = engine.queue.value
                q.order.map { q.entries[it].toMediaItem() }
            } else {
                emptyList()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val q = engine.queue.value
            val entry = mediaId.toLongOrNull()?.let { uid -> q.entries.firstOrNull { it.uid == uid } }
                ?: q.entries.firstOrNull { it.track.id == mediaId }
            return Futures.immediateFuture(
                entry?.let { LibraryResult.ofItem(it.toMediaItem(), null) }
                    ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
            )
        }
    }

    private companion object {
        const val ROOT_ID = "root"
    }
}
