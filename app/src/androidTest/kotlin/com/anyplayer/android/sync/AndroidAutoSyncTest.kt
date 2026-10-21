package com.anyplayer.android.sync

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anyplayer.android.MainActivity
import com.anyplayer.android.core.model.AppJson
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.db.AppDatabase
import com.anyplayer.android.data.db.entity.QueueEntryEntity
import com.anyplayer.android.data.db.entity.QueueMetaEntity
import com.anyplayer.android.data.settings.SyncSettingsStore
import com.anyplayer.android.data.sync.AppStartup
import com.anyplayer.android.data.sync.SyncEngine
import com.anyplayer.android.playback.PlaybackEngine
import com.anyplayer.android.testing.Device
import com.anyplayer.android.testing.Device.awaitUntil
import com.anyplayer.android.testing.Device.onMain
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android Auto drives the app through the real media session (a [MediaController] stands in for the car),
 * the real engine plays the queue through ExoPlayer, and sync talks to [FakeSyncServer]. The server copy
 * is what this phone pushed before the drive; nothing the car did may be rewound to it.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AndroidAutoSyncTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val notifications = Device.notificationPermission()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()

    // Providers: the engine must not restore before a test has seeded persisted state.
    @Inject lateinit var engine: Provider<PlaybackEngine>
    @Inject lateinit var sync: Provider<SyncEngine>
    @Inject lateinit var startup: AppStartup
    @Inject lateinit var syncSettings: SyncSettingsStore
    @Inject lateinit var base: SyncBaseStore
    @Inject lateinit var database: AppDatabase

    private lateinit var server: FakeSyncServer
    private lateinit var songs: List<Track>
    private var car: MediaController? = null
    private var app: ActivityScenario<MainActivity>? = null

    @Before fun setUp() = runBlocking {
        hilt.inject()
        server = FakeSyncServer()
        songs = listOf("Car Song One", "Car Song Two", "Car Song Three", "Car Song Four").mapIndexed { i, title -> server.track(i + 1, title) }
        syncSettings.update {
            it.copy(serverTarget = server.target, authToken = "test-token",
                syncPlaylists = false, syncProviderConfiguration = false, syncSettings = false)
        }
        // Before the drive the phone pushed "song one, paused"; the server and the merge base agree on it.
        server.appState = FakeSyncServer.appState(songs[0], songs, "paused")
        base.set(SyncBase.APP_STATE, syncBaseTarget(syncSettings.awaitLoaded()), appStateSignature(songs[0], songs, false, "off"))
    }

    @After fun tearDown() {
        car?.let { controller -> onMain { controller.release() } }
        app?.close()
        server.shutdown()
    }

    @Test fun syncStartingAfterTheCarSkippedAheadKeepsCarPlaybackAndPushesIt() = runBlocking {
        queueOnPhone(startAt = 0)
        val car = connectCar()
        onMain { car.play() }
        skipInCar(car, to = 2)
        // Song three plays steadily, so no engine change can overlap the fetch and mask a rewind.
        awaitUntil { engine.get().player.value.status == PlaybackStateType.PLAYING }
        kotlinx.coroutines.delay(1_500)
        // Sync starts late (e.g. a slow provider refresh) while the car keeps playing. Started directly:
        // full startup also activates the DJ, whose own startup change can overlap the fetch and hide a rewind.
        sync.get().start()
        awaitPullComplete()

        server.awaitPush { FakeSyncServer.currentId(it) == "car-3" }
        assertCurrent(songs[2])
        assertTrue(engine.get().player.value.playRequested)
        assertEquals("Car Song Three", onMain { car.mediaMetadata.title?.toString() })
    }

    @Test fun openingTheAppAfterAnOfflineDriveDoesNotRewindToAStaleRepublish() = runBlocking {
        queueOnPhone(startAt = 0)
        startup.start()
        val car = connectCar()
        onMain { car.play() }
        awaitPullComplete()
        server.awaitSocket()

        // Out of range of the self-hosted server: every push during the drive fails.
        server.goOffline()
        skipInCar(car, to = 2)
        onMain { car.pause() }
        awaitUntil { !engine.get().player.value.playRequested }

        // Home again: the user opens the app and the desktop re-publishes what the server still holds.
        server.goOnline()
        openApp()
        server.republishFromOtherClient(server.awaitSocket(timeoutSeconds = 90))

        server.awaitPush { FakeSyncServer.currentId(it) == "car-3" }
        assertCurrent(songs[2])
        assertAppShows("Car Song Three")
    }

    @Test fun reopeningTheAppInANewProcessAfterAnOfflineDriveKeepsTheCarPosition() = runBlocking {
        // A previous process played up to song three in the car and could not push it before being killed.
        database.queueDao().replace(songs.mapIndexed { seq, track -> QueueEntryEntity(seq + 1L, seq, AppJson.encodeToString(track)) },
            QueueMetaEntity(orderUids = "1,2,3,4", cursorUid = 3, contentVersion = 1))

        startup.start()
        openApp()
        awaitPullComplete()

        server.awaitPush { FakeSyncServer.currentId(it) == "car-3" }
        assertCurrent(songs[2])
        assertAppShows("Car Song Three")
    }

    private suspend fun queueOnPhone(startAt: Int) {
        val engine = engine.get()
        engine.awaitRestored()
        engine.setQueue(songs, startAt, autoPlay = false)
        awaitUntil { engine.queue.value.current?.track == songs[startAt] }
    }

    private fun connectCar(): MediaController = Device.connectController().also { car = it }

    private suspend fun skipInCar(car: MediaController, to: Int) {
        val engine = engine.get()
        while (engine.queue.value.cursor < to) {
            val before = engine.queue.value.cursor
            onMain { car.seekToNext() }
            awaitUntil { engine.queue.value.cursor == before + 1 }
        }
        assertCurrent(songs[to])
    }

    private fun openApp() { app = ActivityScenario.launch(MainActivity::class.java) }

    private suspend fun awaitPullComplete() = withTimeout(60_000) { sync.get().state.first { it.status == "Sync pull complete." } }

    private fun assertCurrent(track: Track) = assertEquals(track, engine.get().queue.value.current?.track)

    private fun assertAppShows(title: String) =
        compose.waitUntil(15_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
}
