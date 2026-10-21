package com.anyplayer.android.settings

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anyplayer.android.MainActivity
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.playlists.PlaylistRepository
import com.anyplayer.android.data.settings.*
import com.anyplayer.android.data.store.SecurePrefs
import com.anyplayer.android.data.sync.SyncEngine
import com.anyplayer.android.data.transfer.ConfigFiles
import com.anyplayer.android.dj.*
import com.anyplayer.android.playback.PlaybackEngine
import com.anyplayer.android.sync.FakeSyncServer
import com.anyplayer.android.testing.Audio
import com.anyplayer.android.testing.Device
import com.anyplayer.android.testing.Device.awaitUntil
import com.anyplayer.android.testing.Device.onMain
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real Compose settings, encrypted preferences, Room, sync socket and ExoPlayer.
 * Persistence checks read the app's own preference files through the injected [SecurePrefs] or
 * [Context.getSharedPreferences]; no extra store instances are built, because each one runs its own writer.
 * Activity recreation checks UI restoration from retained state, not process-death persistence. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val notifications = Device.notificationPermission()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()

    @Inject lateinit var engine: Provider<PlaybackEngine>
    @Inject lateinit var sync: Provider<SyncEngine>
    @Inject lateinit var syncSettings: SyncSettingsStore
    @Inject lateinit var playerSettings: PlayerSettingsStore
    @Inject lateinit var djSettings: DjSettingsStore
    @Inject lateinit var secure: SecurePrefs
    @Inject lateinit var playlists: PlaylistRepository
    @Inject lateinit var config: ConfigFiles
    @Inject lateinit var dj: Provider<DjController>
    private lateinit var server: FakeSyncServer
    private var activity: ActivityScenario<MainActivity>? = null
    private var controller: MediaController? = null

    @Before fun setUp(): Unit = runBlocking {
        hilt.inject()
        server = FakeSyncServer()
        syncSettings.update { it.copy(serverTarget = server.target, authToken = "settings-token") }
        playerSettings.awaitLoaded()
        djSettings.awaitLoaded()
    }

    @After fun tearDown() {
        controller?.let { onMain { it.release() } }
        if (::engine.isInitialized) onMain { engine.get().pause() }
        activity?.close()
        if (::server.isInitialized) server.shutdown()
    }

    @Test fun imeDoneCommitsTargetAndTokenAndDomainChipsSurviveRecreation() = runBlocking {
        openSettings()
        compose.onNodeWithText("Sync Server Target").performTextReplacement(server.target + "/new")
        compose.onNodeWithText("Sync Server Target").performImeAction()
        awaitUntil { syncSettings.preferences.value.serverTarget == server.target + "/new" }
        compose.onNodeWithText("Sync Auth Token").performTextReplacement("saved-fixture")
        compose.onNodeWithText("Sync Auth Token").performImeAction()
        activity!!.onActivity { host ->
            (host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
        }
        awaitUntil { syncSettings.preferences.value.authToken == "saved-fixture" }
        listOf("app_state", "playlists", "provider_configuration", "settings").forEach { click(it) }
        awaitUntil { syncSettings.preferences.value.let { !it.syncAppState && !it.syncPlaylists && !it.syncProviderConfiguration && !it.syncSettings } }
        val persisted = secure.open(SecurePrefs.SYNC_PREFERENCES)
        awaitUntil {
            persisted.getString("sync_preferences_json", null)?.let { json ->
                AppJson.decodeFromString<SyncPreferences>(json).let {
                    it.serverTarget == server.target + "/new" && it.authToken == "saved-fixture" &&
                        !it.syncAppState && !it.syncPlaylists && !it.syncProviderConfiguration && !it.syncSettings
                }
            } == true
        }
        activity!!.recreate()
        listOf("app_state", "playlists", "provider_configuration", "settings").forEach {
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasText(it) and isNotSelected()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(it).assertIsNotSelected()
        }
    }

    @Test fun emptyServerConnectPushesEverySelectedNamespace() = runBlocking {
        val song = server.track(1, "Local queue")
        engine.get().awaitRestored()
        onMain { engine.get().setQueue(listOf(song), autoPlay = false) }
        val local = playlists.create("Local collection", PlaylistType.STANDARD)
        playlists.addTrack(local.id, song)
        openSettings()
        click("Connect")
        status("Connected. Pushed local data to server.")
        listOf("app-state", "settings", "playlists", "provider-configuration").forEach { namespace ->
            assertTrue("Missing $namespace PUT", server.server.requestsTo("/v1/state/$namespace", "PUT").isNotEmpty())
        }
        assertEquals("car-1", FakeSyncServer.currentId(server.appState))
        assertTrue(server.namespaces.getValue("playlists").toString().contains("Local collection"))
        assertEquals("Bearer settings-token", server.server.requestsTo("/v1/snapshot").first().headers["Authorization"])
    }

    @Test fun conflictKeepThisDeviceOverwritesServerPlaylistsAndPlayback() = runBlocking {
        val local = playlists.create("Keep phone", PlaylistType.STANDARD)
        val song = server.track(1, "Phone song")
        engine.get().awaitRestored()
        onMain { engine.get().setQueue(listOf(song), autoPlay = false) }
        seedServerPlaylist("Discard server")
        openSettings()
        click("Connect")
        status("Sync server already has data")
        click("Keep This Device")
        status("Pushed local data to server.")
        assertEquals("car-1", FakeSyncServer.currentId(server.appState))
        assertTrue(server.namespaces.getValue("playlists").toString().contains(local.id))
        assertFalse(server.namespaces.getValue("playlists").toString().contains("Discard server"))
    }

    @Test fun conflictUseServerDataReplacesPlaylistsAndAppliesPlaybackAndNormalization() = runBlocking {
        playlists.create("Discard phone", PlaylistType.STANDARD)
        seedServerPlaylist("Server collection")
        val song = server.track(2, "Server song")
        server.appState = FakeSyncServer.appState(song, listOf(song), "paused")
        server.namespaces["settings"] = buildJsonObject { put("audio_normalization_enabled", true) }
        openSettings()
        click("Connect")
        status("Sync server already has data")
        click("Use Server Data")
        status("Sync pull complete.")
        assertEquals(listOf("Server collection"), playlists.customPlaylists.first().map { it.name })
        awaitUntil { engine.get().queue.value.current?.track?.id == song.id && engine.get().player.value.normalization }
        compose.onNodeWithTag("tab_NOW_PLAYING").performClick()
        status("Server song")
        assertFalse(engine.get().player.value.playRequested)
    }

    @Test fun cancellingConflictPreservesLocalDataAndReportsCancellation() = runBlocking {
        val local = playlists.create("Untouched phone", PlaylistType.STANDARD)
        seedServerPlaylist("Untouched server")
        openSettings()
        click("Connect")
        status("Sync server already has data")
        click("Cancel")
        status("Sync connect cancelled.")
        assertEquals(listOf(local.id), playlists.customPlaylists.first().map { it.id })
        assertTrue(server.server.log.none { it.method == "PUT" })
    }

    @Test fun cancellingConflictStopsRealtimeAndAutomaticPushes() = runBlocking {
        playlists.create("Untouched phone", PlaylistType.STANDARD)
        seedServerPlaylist("Untouched server")
        val song = server.track(1, "Phone song")
        engine.get().awaitRestored()
        openSettings()
        click("Connect")
        status("Sync server already has data")
        val socket = server.awaitSocket()
        click("Cancel")
        status("Sync connect cancelled.")
        val snapshots = server.server.requestsTo("/v1/snapshot").size
        onMain { engine.get().setQueue(listOf(song), autoPlay = false) }
        playlists.create("After cancel", PlaylistType.STANDARD)
        server.publishFromOtherClient(socket, "app_state")
        server.publishFromOtherClient(socket, "playlists")
        // Absence check: automatic pushes leave within the 2 s throttle and socket events fetch at once.
        kotlinx.coroutines.delay(5_000)
        assertEquals("No PUT after Cancel", emptyList<String>(), server.server.log.filter { it.method == "PUT" }.map { it.path })
        assertEquals("No snapshot GET after Cancel", snapshots, server.server.requestsTo("/v1/snapshot").size)
        assertTrue(server.server.requestsTo("/v1/state/app-state", "GET").isEmpty())
    }

    @Test fun forcePullCancelPreservesThenContinueReplacesLocalPlaylists() = runBlocking {
        val local = playlists.create("Local only", PlaylistType.STANDARD)
        seedServerPlaylist("Replacement")
        openSettings()
        click("Force Pull from Server")
        status("Confirm playlist overwrite")
        click("Cancel")
        absent("Confirm playlist overwrite")
        assertEquals(listOf(local.id), playlists.customPlaylists.first().map { it.id })
        assertTrue(server.server.requestsTo("/v1/snapshot").isEmpty())
        click("Force Pull from Server")
        click("Continue")
        status("Sync pull complete.")
        assertEquals(listOf("Replacement"), playlists.customPlaylists.first().map { it.name })
        // Ordered absence check: had Cancel started a pull, Continue's pull would be the second snapshot.
        assertEquals(1, server.server.requestsTo("/v1/snapshot").size)
        compose.onNodeWithTag("tab_PLAYLISTS").performClick()
        status("Replacement")
        absent("Local only")
    }

    @Test fun unreachableServerReportsExactStatusAndKeepsLocalPlaylist() = runBlocking {
        playlists.create("Offline safe", PlaylistType.STANDARD)
        server.goOffline()
        openSettings()
        click("Connect")
        status("Sync server unavailable.")
        assertEquals(listOf("Offline safe"), playlists.customPlaylists.first().map { it.name })
    }

    @Test fun realtimeOtherClientAddsPlaylistAndChangesNormalizationInUi() = runBlocking {
        syncSettings.update { it.copy(syncAppState = false, syncProviderConfiguration = false) }
        openSettings()
        click("Connect")
        status("Connected. Pushed local data to server.")
        val socket = server.awaitSocket()
        seedServerPlaylist("Desktop arrival")
        server.publishFromOtherClient(socket, "playlists")
        awaitUntil { runBlocking { playlists.customPlaylists.first().any { it.name == "Desktop arrival" } } }
        compose.onNodeWithTag("tab_PLAYLISTS").performClick()
        status("Desktop arrival")
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        server.namespaces["settings"] = buildJsonObject { put("audio_normalization_enabled", true) }
        server.publishFromOtherClient(socket, "settings")
        awaitUntil { engine.get().player.value.normalization }
        selected("Normalize Audio Across Providers")
        awaitUntil { playerPrefs().getBoolean("normalizationEnabled", false) }
    }

    @Test fun normalizationChipPersistsAndUpdatesRealEngineBothWays() = runBlocking {
        openSettings()
        click("Normalize Audio Across Providers")
        awaitUntil { engine.get().player.value.normalization }
        awaitUntil { playerPrefs().getBoolean("normalizationEnabled", false) }
        activity!!.recreate()
        selected("Normalize Audio Across Providers")
        click("Normalize Audio Across Providers")
        awaitUntil { !engine.get().player.value.normalization }
        awaitUntil { !playerPrefs().getBoolean("normalizationEnabled", true) }
    }

    @Test fun aiDjTogglesPersistAcrossRecreationAndGenerationModes() = runBlocking {
        openSettings()
        compose.onNodeWithTag("settings_tab_AI_DJ").performClick()
        click("Enable AI DJ")
        click("Show DJ entries in queue")
        click("This device")
        awaitUntil { playerSettings.settings.value.aiDjEnabled && playerSettings.settings.value.showDjEntriesInQueue && !djSettings.settings.value.remoteGeneration }
        val persisted = secure.open(SecurePrefs.SYNC_PREFERENCES)
        awaitUntil { !persisted.getBoolean("dj_remote_generation", true) }
        awaitUntil { playerPrefs().getBoolean("aiDjEnabled", false) && playerPrefs().getBoolean("showDjEntriesInQueue", false) }
        activity!!.recreate()
        selected("Enable AI DJ")
        selected("Show DJ entries in queue")
        selected("This device")
        click("Sync server")
        awaitUntil { djSettings.settings.value.remoteGeneration }
        awaitUntil { persisted.getBoolean("dj_remote_generation", false) }
        click("Enable AI DJ")
        click("Show DJ entries in queue")
        awaitUntil { !playerSettings.settings.value.aiDjEnabled && !playerSettings.settings.value.showDjEntriesInQueue }
        awaitUntil { !playerPrefs().getBoolean("aiDjEnabled", true) && !playerPrefs().getBoolean("showDjEntriesInQueue", true) }
    }

    @Test fun aiDjUnauthorizedCatalogExplainsTokenAndLogsCanBeCleared(): Unit = runBlocking {
        server.server.json("GET", "/v1/dj-models", 401) { "{}" }
        server.server.json("GET", "/v1/dj-voice-models", 401) { "{}" }
        openSettings()
        compose.onNodeWithTag("settings_tab_AI_DJ").performClick()
        click("Enable AI DJ")
        status("Sync server rejected the auth token. Check the Sync Auth Token setting.")
        assertTrue(server.server.requestsTo("/v1/dj-voice-models").isNotEmpty())
        awaitUntil { dj.get().logs.value.isNotEmpty() }
        click("Enable AI DJ") // Collapse artifact controls, keeping logs visible.
        click("Clear logs")
        status("No AI DJ logs yet.")
        assertTrue(dj.get().logs.value.isEmpty())
        compose.onNodeWithText("Clear logs").assertIsNotEnabled()
    }

    @Test fun remoteDjBreakPlaysBetweenCustomSongsAndPostsPassageReceipt() = runBlocking {
        server.server.json("GET", "/v1/dj-passages") {
            """{"status":"ok","passages":[{"chunk_id":42,"text":"This song was recorded in a studio.","source":"Test archive"}]}"""
        }
        server.server.route("POST", "/v1/dj-breaks") {
            MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(breakWave()))
        }
        server.server.route("POST", "/v1/dj-passages/played") { MockResponse().setResponseCode(204) }
        val songs = (1..10).map { server.track(it, "DJ song $it", 3_000) }
        engine.get().awaitRestored()
        onMain { engine.get().setQueue(songs, autoPlay = false) }
        openSettings()
        compose.onNodeWithTag("settings_tab_AI_DJ").performClick()
        click("Enable AI DJ")
        click("Show DJ entries in queue")
        awaitUntil(30_000, "remote WAV normalized and ready; logs=${dj.get().logs.value}") { dj.get().preparationStatus.value == PreparationStatus.READY }
        val request = server.server.requestsTo("/v1/dj-breaks", "POST").single()
        val targetTitle = AppJson.parseToJsonElement(request.body).jsonObject.getValue("title").jsonPrimitive.content
        assertTrue(targetTitle in songs.map { it.title })
        assertEquals("Bearer settings-token", request.headers["Authorization"])
        compose.onNodeWithTag("tab_NOW_PLAYING").performClick()
        // The DJ entry is inserted into the queue asynchronously after preparation; scroll once it exists.
        compose.waitUntil(30_000) {
            runCatching { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("AnyPlayer DJ")) }.isSuccess
        }
        status("AnyPlayer DJ")
        controller = Device.connectController()
        onMain { controller!!.play() }
        awaitUntil(40_000, "DJ break plays; logs=${dj.get().logs.value}") { engine.get().player.value.activeBreak != null }
        val before = engine.get().queue.value.current!!.track
        assertEquals(songs[songs.indexOfFirst { it.title == targetTitle } - 1].id, before.id)
        awaitUntil { onMain { controller!!.mediaMetadata.title?.toString() == "AnyPlayer DJ" } }
        val receipt = server.server.awaitRequest { it.method == "POST" && it.path == "/v1/dj-passages/played" }
        val receiptBody = AppJson.parseToJsonElement(receipt.body).jsonObject
        assertEquals(listOf(42), receiptBody.getValue("chunk_ids").jsonArray.map { it.jsonPrimitive.int })
        assertTrue(receiptBody.getValue("event_id").jsonPrimitive.content.isNotBlank())
        awaitUntil(30_000) { engine.get().player.value.activeBreak == null && engine.get().queue.value.current?.track?.title == targetTitle }
        awaitUntil { onMain { controller!!.mediaMetadata.title?.toString() == targetTitle } }
        assertTrue(engine.get().player.value.playRequested)
    }

    // Speech normalization correctly discards all-silent input; use audible PCM, not the song fixture.
    private fun breakWave(): ByteArray = Audio.silentWav(6_000).also { bytes ->
        val pcm = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (sample in 0 until (bytes.size - 44) / 2) {
            pcm.putShort(44 + sample * 2, (8_000 * kotlin.math.sin(2 * Math.PI * 440 * sample / 8_000)).toInt().toShort())
        }
    }

    private fun openSettings() {
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        status("Sync Server Target")
    }

    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }
    private fun status(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(text)[0].assertExists()
    }
    private fun selected(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText(text) and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).performScrollTo().assertIsSelected()
    }

    private fun absent(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(text).assertDoesNotExist()
    }

    private fun playerPrefs() = Device.context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)

    private suspend fun seedServerPlaylist(name: String) {
        val playlist = playlists.create(name, PlaylistType.STANDARD)
        server.namespaces["playlists"] = config.exportPlaylistsJson().jsonArray.let { entries ->
            JsonArray(entries.filter { it.jsonObject.getValue("playlist").jsonObject.getValue("id").jsonPrimitive.content == playlist.id })
        }
        playlists.delete(playlist.id)
    }
}
