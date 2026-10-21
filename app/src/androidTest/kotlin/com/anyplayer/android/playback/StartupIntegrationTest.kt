package com.anyplayer.android.playback

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anyplayer.android.MainActivity
import com.anyplayer.android.core.model.AppJson
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.auth.ConnectionStore
import com.anyplayer.android.data.auth.StoredConnection
import com.anyplayer.android.data.catalog.CatalogRepository
import com.anyplayer.android.data.db.AppDatabase
import com.anyplayer.android.data.db.entity.AppCacheEntryEntity
import com.anyplayer.android.data.settings.SyncSettingsStore
import com.anyplayer.android.data.sync.AppStartup
import com.anyplayer.android.sync.FakeSyncServer
import com.anyplayer.android.testing.Device
import com.anyplayer.android.testing.Device.awaitUntil
import com.anyplayer.android.testing.Device.onMain
import com.anyplayer.android.testing.TestServer
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Startup runs explicitly: HiltTestApplication intentionally does not execute Application startup. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class StartupIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val notifications = Device.notificationPermission()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()

    @Inject lateinit var startup: AppStartup
    @Inject lateinit var engine: Provider<PlaybackEngine>
    @Inject lateinit var settings: SyncSettingsStore
    @Inject lateinit var connections: ConnectionStore
    @Inject lateinit var database: AppDatabase
    @Inject lateinit var catalog: Provider<CatalogRepository>

    private lateinit var server: TestServer
    private var syncServer: FakeSyncServer? = null
    private var app: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        hilt.inject()
        server = TestServer()
    }

    @After fun tearDown() {
        engine.get().pause()
        app?.close()
        syncServer?.shutdown() ?: server.close()
    }

    @Test fun foregroundOpensNetworkGateImportsLegacyCacheAndCompletesStartup() = runBlocking {
        configureSync()
        val cached = Track("legacy-track", "Legacy Cached Song", "Legacy Artist", source = SourceType.JELLYFIN)
        database.catalogCacheDao().putLegacy(AppCacheEntryEntity("provider_playlist_tracks_legacy", 
            """{"source":"jellyfin","playlistId":"legacy","tracks":${AppJson.encodeToString(listOf(cached))}}""", 2, "2026-01-01T00:00:00Z"))
        startup.start()
        waitingAtGate()
        assertEquals(1, database.catalogCacheDao().legacyEntries().size)
        assertTrue(catalog.get().cachedTracks(SourceType.JELLYFIN, "legacy").isEmpty())
        // Negative network assertion needs a bounded observation interval after the coordinator is waiting.
        delay(500)
        assertTrue("Background startup must not contact configured sync target", server.log.isEmpty())
        app = ActivityScenario.launch(MainActivity::class.java)
        completed("Startup complete. No providers restored; running with local/cached data.")
        awaitUntil(message = "foreground startup reaches sync server") { server.requestsTo("/v1/state/app-state", "GET").isNotEmpty() || server.requestsTo("/v1/snapshot", "GET").isNotEmpty() }
        assertEquals(listOf(cached), catalog.get().cachedTracks(SourceType.JELLYFIN, "legacy"))
        assertTrue(database.catalogCacheDao().legacyEntries().isEmpty())
        compose.onNodeWithTag("tab_NOW_PLAYING").assertIsDisplayed()
        // Successful startup removes the banner; the final message remains available in status.
        compose.onNodeWithText("Startup complete. No providers restored; running with local/cached data.").assertDoesNotExist()
        assertTrue(startup.status.value.warnings.isEmpty())
    }

    @Test fun playbackWithoutActivityAlsoOpensStartupNetworkGate() = runBlocking {
        configureSync()
        startup.start()
        waitingAtGate()
        val car = Device.connectController()
        try {
            // Connecting a cold car browser/controller must still leave startup network work gated.
            delay(500)
            assertTrue(server.log.isEmpty())
            val song = Track("gate-song", "Gate Song", "Integration Artist", durationMs = 30_000,
                source = SourceType.CUSTOM, url = server.audio("/audio/gate.wav", 30_000))
            engine.get().setQueue(listOf(song), autoPlay = false)
            awaitUntil { engine.get().queue.value.current?.track == song }
            onMain { car.play() }
            awaitUntil { engine.get().player.value.status == PlaybackStateType.PLAYING }
            completed("Startup complete. No providers restored; running with local/cached data.")
            awaitUntil(message = "playback opens sync gate") { server.log.any { it.path.startsWith("/v1/") } }
            assertTrue(server.requestsTo("/audio/gate.wav").isNotEmpty())
        } finally { onMain { car.release() } }
    }

    @Test fun retryStartupClearsProviderFailureWarningAndRefreshesPlaylist() = runBlocking {
        val fail = AtomicBoolean(true)
        server.json("GET", "/Users") { """[{"Id":"test-user","Name":"Test User"}]""" }
        server.route("GET", "/Users/test-user/Items") {
            if (fail.get()) MockResponse().setResponseCode(401)
            else MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"Items":[{"Id":"recovered","Name":"Recovered Playlist","ChildCount":2}],"TotalRecordCount":1}""")
        }
        connections.update(SourceType.JELLYFIN) { StoredConnection(SourceType.JELLYFIN, serverUrl = server.baseUrl, token = "test-token", jellyfinUserId = "test-user") }
        startup.start()
        waitingAtGate()
        app = ActivityScenario.launch(MainActivity::class.java)
        completed("Startup complete. 1 provider session(s) restored.")
        assertTrue(startup.status.value.warnings.single().startsWith("Playlist refresh failed: JELLYFIN:"))
        compose.onNodeWithText("Retry Startup").assertIsDisplayed()
        val failedRequests = server.requestsTo("/Users/test-user/Items").size
        assertTrue(failedRequests > 0)
        fail.set(false)
        compose.onNodeWithText("Retry Startup").performClick()
        awaitUntil(message = "startup retry finishes without warning") {
            !startup.status.value.inProgress && startup.status.value.warnings.isEmpty() && server.requestsTo("/Users/test-user/Items").size > failedRequests
        }
        assertEquals(listOf("Recovered Playlist"), withTimeout(30_000) { catalog.get().playlists.first { it.any { playlist -> playlist.id == "recovered" } } }.map { it.name })
        assertEquals("Startup complete. 1 provider session(s) restored.", startup.status.value.message)
        compose.onNodeWithText("Startup complete. 1 provider session(s) restored.").assertDoesNotExist()
        compose.onNodeWithText("Retry Startup").assertDoesNotExist()
    }

    private suspend fun configureSync() {
        syncServer = FakeSyncServer(server)
        settings.update { it.copy(serverTarget = server.baseUrl, authToken = "startup-test", syncPlaylists = false, syncProviderConfiguration = false, syncSettings = false) }
    }

    private suspend fun waitingAtGate() = awaitUntil(message = "startup waits for foreground or playback") {
        startup.status.value.inProgress && startup.status.value.message == "Waiting for playback or app..."
    }

    private suspend fun completed(message: String) = awaitUntil(message = "startup completes") {
        !startup.status.value.inProgress && startup.status.value.message == message
    }
}
