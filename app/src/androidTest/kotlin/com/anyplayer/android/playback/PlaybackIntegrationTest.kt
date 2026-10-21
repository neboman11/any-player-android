package com.anyplayer.android.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionError
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anyplayer.android.core.model.AppJson
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.db.AppDatabase
import com.anyplayer.android.data.db.entity.QueueEntryEntity
import com.anyplayer.android.data.db.entity.QueueMetaEntity
import com.anyplayer.android.data.store.SecurePrefs
import com.anyplayer.android.playback.persistence.LegacyPlaybackState
import com.anyplayer.android.testing.Device
import com.anyplayer.android.testing.Device.awaitUntil
import com.anyplayer.android.testing.Device.onMain
import com.anyplayer.android.testing.TestServer
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real session, decoder, encrypted preferences and on-disk Room; each method gets a fresh process. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PlaybackIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val notifications = Device.notificationPermission()

    @Inject lateinit var engineProvider: Provider<PlaybackEngine>
    @Inject lateinit var database: AppDatabase
    @Inject lateinit var secure: SecurePrefs

    private lateinit var server: TestServer
    private var browser: MediaBrowser? = null
    private val engine get() = engineProvider.get()

    @Before fun setUp() {
        hilt.inject()
        server = TestServer()
    }

    @After fun tearDown() = runBlocking {
        engine.pause()
        browser?.let { onMain { it.release() } }
        server.close()
    }

    @Test fun browserExposesPlayOrderPagingAndUidOrTrackLookup() = runBlocking {
        val songs = songs(4)
        seed(songs, order = listOf(3, 1, 4, 2), cursorUid = 4)
        playerPrefs().edit().putBoolean("shuffle", true).commit()
        engine.awaitRestored()
        val car = connect()
        val root = onMain { car.getLibraryRoot(null) }.get(30, TimeUnit.SECONDS)
        assertEquals("root", root.value!!.mediaId)
        assertEquals("Any Player", root.value!!.mediaMetadata.title.toString())
        assertEquals(true, root.value!!.mediaMetadata.isBrowsable)
        assertEquals(false, root.value!!.mediaMetadata.isPlayable)
        val first = onMain { car.getChildren("root", 0, 2, null) }.get(30, TimeUnit.SECONDS).value!!
        val second = onMain { car.getChildren("root", 1, 2, null) }.get(30, TimeUnit.SECONDS).value!!
        assertEquals(listOf("3", "1", "4", "2"), (first + second).map { it.mediaId })
        assertEquals(listOf(songs[2].title, songs[0].title), first.map { it.mediaMetadata.title.toString() })
        assertTrue(onMain { car.getChildren("root", 2, 2, null) }.get(30, TimeUnit.SECONDS).value!!.isEmpty())
        assertTrue(onMain { car.getChildren("missing-parent", 0, 2, null) }.get(30, TimeUnit.SECONDS).value!!.isEmpty())
        val byUid = onMain { car.getItem("4") }.get(30, TimeUnit.SECONDS).value!!
        val byTrack = onMain { car.getItem(songs[3].id) }.get(30, TimeUnit.SECONDS).value!!
        assertEquals("4", byUid.mediaId)
        assertEquals(byUid.mediaMetadata, byTrack.mediaMetadata)
        assertEquals(songs[3].artist, byTrack.mediaMetadata.artist.toString())
        assertEquals(SessionError.ERROR_BAD_VALUE, onMain { car.getItem("unknown") }.get(30, TimeUnit.SECONDS).resultCode)
    }

    @Test fun controllerTransportSeekPreviousModesAndVolumeReachEngineAndSession() = runBlocking {
        val songs = songs(3)
        queue(songs, 1)
        val car = connect()
        onMain { car.play() }
        playing(songs[1])
        awaitUntil(message = "session reports playing") { onMain { car.isPlaying && car.mediaMetadata.title.toString() == songs[1].title } }
        onMain { car.pause() }
        awaitUntil { !engine.player.value.playRequested && onMain { !car.playWhenReady } }
        onMain { car.seekTo(7_000) }
        awaitUntil(message = "paused seek") { engine.positionMs() in 6_800..7_200 && onMain { car.currentPosition in 6_800..7_200 } }
        onMain { car.seekToPrevious() }
        awaitUntil(message = "previous restarts after three seconds") { engine.positionMs() < 1_000 && onMain { car.currentPosition < 1_000 } }
        assertEquals(songs[1], engine.queue.value.current!!.track)
        onMain { car.seekTo(1_000) }
        awaitUntil { engine.positionMs() in 800..1_200 }
        onMain { car.seekToPrevious() }
        playing(songs[0])
        awaitUntil { onMain { car.mediaMetadata.title.toString() == songs[0].title } }
        onMain { car.seekToNext() }
        playing(songs[1])
        awaitUntil { onMain { car.mediaMetadata.title.toString() == songs[1].title } }
        onMain { car.shuffleModeEnabled = true; car.volume = 0.37f }
        awaitUntil { engine.queue.value.shuffle && engine.player.value.volume == 37 && onMain { car.shuffleModeEnabled && kotlin.math.abs(car.volume - 0.37f) < 0.01f } }
        assertEquals(songs[1], engine.queue.value.current!!.track)
        for ((session, mode) in listOf(Player.REPEAT_MODE_ONE to RepeatMode.ONE, Player.REPEAT_MODE_ALL to RepeatMode.ALL, Player.REPEAT_MODE_OFF to RepeatMode.OFF)) {
            onMain { car.repeatMode = session }
            awaitUntil { engine.queue.value.repeat == mode && onMain { car.repeatMode == session } }
        }
        onMain { car.shuffleModeEnabled = false; car.pause() }
        awaitUntil { !engine.queue.value.shuffle && !engine.player.value.playRequested && onMain { !car.isPlaying && !car.shuffleModeEnabled } }
        assertEquals(listOf(0, 1, 2), engine.queue.value.order)
    }

    @Test fun setMediaItemsPreservesMatchingQueueButReplacesWithResolvedSubset() = runBlocking {
        val songs = songs(3)
        queue(songs, 1)
        engine.seekTo(4_000)
        awaitUntil { engine.positionMs() == 4_000L }
        val original = engine.queue.value
        val car = connect()
        onMain {
            car.setMediaItems(original.entries.reversed().map { MediaItem.Builder().setMediaId(it.uid.toString()).build() }, 0, 0)
            // Player commands share a per-controller queue; library calls do not.
            car.repeatMode = Player.REPEAT_MODE_ALL
        }
        awaitUntil(message = "queued player command follows setMediaItems") { engine.queue.value.repeat == RepeatMode.ALL }
        assertEquals(original.copy(repeat = RepeatMode.ALL), engine.queue.value)
        assertEquals(4_000L, engine.positionMs())
        onMain { car.setMediaItems(listOf(MediaItem.Builder().setMediaId(songs[2].id).build()), 0, 2_000) }
        awaitUntil(message = "resolved subset replaces queue") { engine.queue.value.entries.map { it.track } == listOf(songs[2]) }
        awaitUntil { engine.positionMs() == 2_000L && onMain { car.mediaItemCount == 1 && car.mediaMetadata.title.toString() == songs[2].title } }
        assertFalse(engine.player.value.playRequested)
    }

    @Test fun restoredQueueCursorAndPositionAreAvailableToCarBeforePlayback() = runBlocking {
        val songs = songs(3)
        seed(songs, order = listOf(3, 1, 2), cursorUid = 1)
        playerPrefs().edit().putBoolean("shuffle", true).putString("repeat", "ALL")
            .putInt("volume", 42).putLong("positionMs", 6_000).putBoolean("normalizationEnabled", true).commit()
        engine.awaitRestored()
        val car = connect()
        awaitUntil { onMain { car.mediaItemCount == 3 && car.currentMediaItem?.mediaId == "1" && car.currentPosition == 6_000L } }
        assertEquals(listOf("3", "1", "2"), onMain { (0 until car.mediaItemCount).map { car.getMediaItemAt(it).mediaId } })
        assertEquals(1, onMain { car.currentMediaItemIndex })
        assertEquals(Player.REPEAT_MODE_ALL, onMain { car.repeatMode })
        assertEquals(0.42f, onMain { car.volume }, 0.01f)
        assertTrue(onMain { car.shuffleModeEnabled })
        assertFalse(onMain { car.playWhenReady })
        assertTrue(engine.player.value.normalization)
        assertTrue("Cold restore/browse must not fetch audio", server.log.isEmpty())
        onMain { car.play() }
        playing(songs[0])
        assertTrue(engine.positionMs() >= 6_000)
        assertEquals(songs[0].title, onMain { car.mediaMetadata.title.toString() })
    }

    @Test fun exoAutomaticallyAdvancesThenStopsAtEndOfQueue() = runBlocking {
        val songs = songs(2, 2_000)
        queue(songs)
        val car = connect()
        onMain { car.play() }
        playing(songs[0])
        playing(songs[1])
        awaitUntil { onMain { car.mediaMetadata.title.toString() == songs[1].title } }
        awaitUntil(message = "queue end pauses") { !engine.player.value.playRequested && engine.player.value.status == PlaybackStateType.PAUSED }
        assertEquals(songs[1], engine.queue.value.current!!.track)
        assertTrue(engine.positionMs() >= 1_800)
        awaitUntil { onMain { !car.isPlaying && !car.playWhenReady } }
    }

    @Test fun repeatOneReplaysRealAudioWithoutAdvancingQueue() = runBlocking {
        val songs = songs(2, 2_000)
        queue(songs)
        val starts = CopyOnWriteArrayList<Long>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { if (it is EngineEvent.TrackStarted) starts += it.uid } }
        try {
            val car = connect()
            onMain { car.repeatMode = Player.REPEAT_MODE_ONE; car.play() }
            playing(songs[0])
            awaitUntil(message = "first pass reaches end") { engine.positionMs() >= 1_300 }
            awaitUntil(message = "repeat-one decoder transition") { starts.size >= 2 && engine.positionMs() < 1_000 }
            assertEquals(setOf(engine.queue.value.current!!.uid), starts.toSet())
            assertEquals(songs[0], engine.queue.value.current!!.track)
            assertTrue(onMain { car.isPlaying })
            assertEquals(Player.REPEAT_MODE_ONE, onMain { car.repeatMode })
        } finally { collector.cancelAndJoin() }
    }

    @Test fun repeatAllWrapsFromLastTrackToFirst() = runBlocking {
        val songs = songs(2, 2_000)
        queue(songs, 1)
        val car = connect()
        onMain { car.repeatMode = Player.REPEAT_MODE_ALL; car.play() }
        playing(songs[1])
        playing(songs[0])
        awaitUntil { onMain { car.mediaMetadata.title.toString() == songs[0].title && car.isPlaying } }
        assertEquals(0, engine.queue.value.cursor)
        assertTrue(engine.player.value.playRequested)
    }

    @Test fun httpFailureIsRetriedBeforeSkippingToPlayableSuccessor() = runBlocking {
        val songs = songs(2)
        server.route("GET", "/audio/${songs[0].id}.wav") { MockResponse().setResponseCode(500) }
        queue(songs)
        val car = connect()
        onMain { car.play() }
        playing(songs[1])
        assertTrue("Initial request plus three backend retries", server.requestsTo("/audio/${songs[0].id}.wav").size >= 4)
        assertTrue(server.requestsTo("/audio/${songs[1].id}.wav").isNotEmpty())
        awaitUntil { onMain { car.isPlaying && car.mediaMetadata.title.toString() == songs[1].title } }
        assertNull(engine.player.value.error)
    }

    @Test fun missingUrlIsSkippedWithUserVisibleCantPlayError() = runBlocking {
        val songs = songs(2)
        val unavailable = songs[0].copy(url = null, title = "Missing stream")
        engine.awaitRestored()
        engine.setQueue(listOf(unavailable, songs[1]), autoPlay = false)
        awaitUntil { engine.queue.value.current?.track == songs[1] && engine.player.value.error == "Can't play \"Missing stream\"" }
        assertEquals("Can't play \"Missing stream\"", engine.player.value.error)
        val car = connect()
        onMain { car.play() }
        playing(songs[1])
        awaitUntil { onMain { car.mediaMetadata.title.toString() == songs[1].title } }
    }

    @Test fun playingPostsMediaNotificationWithCurrentTitle() = runBlocking {
        val songs = songs(1)
        queue(songs)
        val car = connect()
        onMain { car.play() }
        playing(songs[0])
        val manager = Device.context.getSystemService(NotificationManager::class.java)
        awaitUntil(message = "media session notification shows current title") {
            manager.activeNotifications.any {
                it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) &&
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == songs[0].title
            }
        }
        val notification = manager.activeNotifications.single {
            it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        }.notification
        assertEquals(Notification.CATEGORY_TRANSPORT, notification.category)
        assertNotNull(notification.extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_MEDIA_SESSION))
    }

    /** Writes are checked through read-only Room/prefs access; seeded restore is covered separately. */
    @Test fun enginePersistsQueueCursorAndPlaybackFlags() = runBlocking {
        val songs = songs(3)
        queue(songs)
        engine.setShuffle(true)
        awaitUntil { engine.queue.value.shuffle }
        engine.next()
        awaitUntil { engine.queue.value.current!!.track != songs[0] }
        engine.pause()
        awaitUntil { !engine.player.value.playRequested && engine.player.value.status == PlaybackStateType.PAUSED }
        engine.seekTo(5_000)
        engine.setVolume(31)
        engine.setRepeat(RepeatMode.ALL)
        engine.setNormalization(true)
        awaitUntil { engine.positionMs() == 5_000L && engine.player.value.volume == 31 && engine.player.value.normalization }
        val expected = engine.queue.value
        awaitUntil(message = "settings persisted") {
            playerPrefs().let {
                it.getLong("positionMs", -1) == 5_000L && it.getInt("volume", -1) == 31 &&
                    it.getBoolean("shuffle", false) && it.getString("repeat", null) == "ALL" &&
                    it.getBoolean("normalizationEnabled", false)
            }
        }
        // Poll suspend DAO from the test thread, never main; content is deliberately debounced.
        awaitUntil(message = "queue persisted") {
            runBlocking {
                database.queueDao().snapshot().let {
                    it.entries.map { row -> row.uid } == expected.entries.map { entry -> entry.uid } &&
                        it.entries.map { row -> AppJson.decodeFromString<Track>(row.trackJson) } == expected.entries.map { entry -> entry.track } &&
                        it.meta?.cursorUid == expected.current!!.uid &&
                        it.meta?.orderUids == expected.order.joinToString(",") { i -> expected.entries[i].uid.toString() }
                }
            }
        }
    }

    @Test fun encryptedLegacyPlaybackMigratesOrderFlagsAndRetiresStatus() = runBlocking {
        val songs = songs(3)
        val legacy = LegacyPlaybackState(queue = songs, orderedQueue = listOf(songs[2], songs[0], songs[1]),
            currentQueueIndex = 1, positionMs = 7_000, shuffle = true, repeatMode = RepeatMode.ONE, volume = 63,
            audioNormalizationEnabled = true, audioNormalizationStrictMode = true, showDjEntriesInQueue = true)
        val prefs = secure.open(SecurePrefs.PLAYBACK_STATE)
        assertTrue(prefs.edit().putString("status", AppJson.encodeToString(legacy)).commit())
        engine.awaitRestored()
        assertEquals(listOf(2, 0, 1), engine.queue.value.order)
        assertEquals(songs[1], engine.queue.value.current!!.track)
        assertEquals(2, engine.queue.value.cursor)
        assertEquals(7_000L, engine.positionMs())
        assertEquals(63, engine.player.value.volume)
        assertEquals(RepeatMode.ONE, engine.queue.value.repeat)
        assertTrue(engine.queue.value.shuffle)
        assertTrue(engine.player.value.normalization)
        assertFalse(prefs.contains("status"))
        val snapshot = database.queueDao().snapshot()
        assertEquals("3,1,2", snapshot.meta!!.orderUids)
        assertEquals(2L, snapshot.meta!!.cursorUid)
        assertEquals(songs, snapshot.entries.map { AppJson.decodeFromString<Track>(it.trackJson) })
        awaitUntil(message = "legacy playback flags persisted") {
            playerPrefs().let {
                it.getInt("volume", -1) == 63 && it.getLong("positionMs", -1) == 7_000L &&
                    it.getBoolean("shuffle", false) && it.getBoolean("normalizationEnabled", false) &&
                    it.getBoolean("normalizationStrict", false) && it.getBoolean("showDjEntriesInQueue", false) &&
                    it.getString("repeat", null) == "ONE"
            }
        }
        assertTrue("Migration must remain local", server.log.isEmpty())
    }

    @Test fun interruptedLegacyMigrationDoesNotOverwriteExistingRoomQueue() = runBlocking {
        val songs = songs(3)
        seed(listOf(songs[2]), cursorUid = 1)
        val prefs = secure.open(SecurePrefs.PLAYBACK_STATE)
        prefs.edit().putString("status", AppJson.encodeToString(LegacyPlaybackState(queue = songs, currentQueueIndex = 0, volume = 55))).commit()
        engine.awaitRestored()
        assertEquals(listOf(songs[2]), engine.queue.value.entries.map { it.track })
        assertEquals(55, engine.player.value.volume)
        assertFalse(prefs.contains("status"))
        assertEquals(1, database.queueDao().entries().size)
    }

    private fun songs(count: Int, durationMs: Long = 30_000) = (1..count).map { n ->
        Track("playback-$n", "Playback Song $n", "Integration Artist", album = "Integration Album", durationMs = durationMs,
            source = SourceType.CUSTOM, url = server.audio("/audio/playback-$n.wav", durationMs))
    }

    private suspend fun queue(songs: List<Track>, startAt: Int = 0) {
        engine.awaitRestored()
        engine.setQueue(songs, startAt, autoPlay = false)
        awaitUntil { engine.queue.value.entries.map { it.track } == songs && engine.queue.value.current?.track == songs[startAt] }
    }

    private suspend fun seed(songs: List<Track>, order: List<Int> = songs.indices.map { it + 1 }, cursorUid: Long = 1) {
        database.queueDao().replace(songs.mapIndexed { i, song -> QueueEntryEntity(i + 1L, i, AppJson.encodeToString(song)) },
            QueueMetaEntity(orderUids = order.joinToString(","), cursorUid = cursorUid, contentVersion = 7))
    }

    private fun playerPrefs() = Device.context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
    private fun connect() = Device.connectBrowser().also { browser = it }
    private suspend fun playing(song: Track) = awaitUntil(message = "playing ${song.title}") {
        engine.queue.value.current?.track == song && engine.player.value.status == PlaybackStateType.PLAYING
    }
}
