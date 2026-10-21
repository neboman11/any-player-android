package com.anyplayer.android.providers

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anyplayer.android.MainActivity
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.auth.AuthRepository
import com.anyplayer.android.data.auth.ConnectionStore
import com.anyplayer.android.data.auth.StoredConnection
import com.anyplayer.android.data.catalog.CatalogRepository
import com.anyplayer.android.data.store.SecureConnectionStore
import com.anyplayer.android.data.store.SecurePrefs
import com.anyplayer.android.data.sync.AppStartup
import com.anyplayer.android.playback.PlaybackEngine
import com.anyplayer.android.testing.Device
import com.anyplayer.android.testing.Device.awaitUntil
import com.anyplayer.android.testing.Device.onMain
import com.anyplayer.android.testing.TestServer
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real settings/auth, encrypted preferences, Room catalog, Compose and ExoPlayer; only HTTP is fake. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ProviderIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val notifications = Device.notificationPermission()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()

    @Inject lateinit var auth: Provider<AuthRepository>
    @Inject lateinit var connections: Provider<ConnectionStore>
    @Inject lateinit var catalog: Provider<CatalogRepository>
    @Inject lateinit var engine: Provider<PlaybackEngine>
    @Inject lateinit var startup: Provider<AppStartup>

    private lateinit var server: TestServer
    private lateinit var jellyfin: FakeJellyfin
    private lateinit var plex: FakePlex
    private var app: ActivityScenario<MainActivity>? = null
    private var foreign: TestServer? = null

    @Before fun setUp() {
        hilt.inject()
        server = TestServer()
        jellyfin = FakeJellyfin(server)
        plex = FakePlex(server)
    }

    @After fun tearDown() {
        if (app != null) onMain { engine.get().pause() }
        app?.close()
        foreign?.close()
        server.close()
    }

    @Test fun jellyfinConnectPageSizeDisconnectAndClearCache() = runBlocking {
        connectionUi(SourceType.JELLYFIN)
    }

    @Test fun plexConnectPageSizeDisconnectAndClearCache() = runBlocking {
        connectionUi(SourceType.PLEX)
    }

    @Test fun jellyfinInvalidTokenAndUnavailableServerShowExactErrors() {
        connectionErrors(SourceType.JELLYFIN, "Jellyfin connection failed: HTTP 401", "Jellyfin connection failed: HTTP 503")
    }

    @Test fun plexInvalidTokenAndUnavailableServerShowExactErrors() {
        connectionErrors(SourceType.PLEX, "Plex connection failed: Plex authentication failed: HTTP 401",
            "Plex connection failed: Plex authentication failed: HTTP 503")
    }

    @Test fun refreshUpdatesBothProvidersAndReopeningTracksWorksOfflineFromRoom() = runBlocking {
        connectBoth()
        openApp()
        tab("PLAYLISTS")
        waitText("Jellyfin Mix")
        waitText("Plex Mix")
        jellyfin.names = listOf("Jellyfin Updated")
        plex.names = listOf("Plex Updated")
        compose.onNodeWithText("Refresh").performClick()
        waitText("Playlist data refreshed successfully!")
        waitText("Jellyfin Updated")
        waitText("Plex Updated")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Jellyfin Mix").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Plex Mix").fetchSemanticsNodes().isEmpty() }
        for (label in listOf("Jellyfin", "Plex")) {
            compose.onNodeWithText("$label Updated").performScrollTo().performClick()
            waitText("Tracks: 3")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("$label Finale", substring = true))
            waitText("$label Finale", substring = true)
            compose.onNodeWithContentDescription("Back").performClick()
        }
        val before = trackRequests().size
        jellyfin.online = false
        plex.online = false
        for (label in listOf("Jellyfin", "Plex")) {
            compose.onNodeWithText("$label Updated").performScrollTo().performClick()
            waitText("Tracks: 3")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("$label Finale", substring = true))
            waitText("$label Finale", substring = true)
            compose.onNodeWithText("Retry").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").performClick()
        }
        assertEquals("Fresh Room cache must avoid even an attempted network fetch", before, trackRequests().size)
    }

    @Test fun jellyfinDistinctPlaylistPlaysWithAuthButForeignAudioDoesNotReceiveIt() = runBlocking {
        connectBoth()
        openPlaylist("Jellyfin Mix")
        compose.onNodeWithContentDescription("Distinct playback").performScrollTo().performClick()
        compose.onNodeWithText("Play Playlist").performScrollTo().performClick()
        awaitPlaying("jf-1")
        assertEquals(listOf("jf-1", "jf-2"), engine.get().queue.value.entries.map { it.track.id })
        val audio = server.awaitRequest { it.path == "/jellyfin/Audio/jf-1/universal" }
        assertTrue(jellyfin.carriesToken(audio))
        assertEquals(jellyfin.token, audio.headers["X-Emby-Token"])
        assertNull(audio.query("api_key"))
        assertEquals("listener", audio.query("UserId"))
        tab("NOW_PLAYING")
        compose.onNodeWithContentDescription("Next").performClick()
        awaitPlaying("jf-2")
        // Prefetch can arrive before the first track's request and be consumed by awaitRequest.
        awaitUntil { server.log.any { it.path == "/jellyfin/Audio/jf-2/universal" } }

        // Different host: genuine audio fetch through the same AudioCache must not leak the key.
        val other = TestServer().also { foreign = it }
        val url = other.audio("/foreign.wav", 60_000).replace("localhost", "127.0.0.1")
        engine.get().setQueue(listOf(Track("foreign", "Foreign audio", "Artist", "Album", 60_000, SourceType.CUSTOM, url)))
        awaitPlaying("foreign")
        val foreignRequest = other.awaitRequest { it.path == "/foreign.wav" }
        assertNull(foreignRequest.headers["Authorization"])
        assertNull(foreignRequest.headers["X-Emby-Token"])
    }

    @Test fun plexPlayPlaylistStreamsSignedAudioAndKeepsDuplicateOccurrences(): Unit = runBlocking {
        connectBoth()
        openPlaylist("Plex Mix")
        compose.onNodeWithText("Play Playlist").performScrollTo().performClick()
        awaitPlaying("px-1")
        assertEquals(listOf("px-1", "px-duplicate", "px-2"), engine.get().queue.value.entries.map { it.track.id })
        val audio = server.awaitRequest { it.path == "/plex/library/parts/px-1/file.wav" }
        assertEquals(plex.token, audio.query("X-Plex-Token"))
        tab("NOW_PLAYING")
        compose.onNodeWithContentDescription("Next").performClick()
        awaitPlaying("px-duplicate")
        // Observe the retained log: prefetch may have preceded the first track's request.
        awaitUntil { server.log.any { it.path == "/plex/library/parts/px-duplicate/file.wav" } }
    }

    @Test fun disconnectJellyfinBlocksCurrentPlaybackButNextStillWorks() = runBlocking { disconnectGate(SourceType.JELLYFIN) }

    @Test fun disconnectPlexBlocksCurrentPlaybackButNextStillWorks() = runBlocking { disconnectGate(SourceType.PLEX) }

    @Test fun searchAllAndProviderChipsFilterTracksAndPlaylists() = runBlocking {
        connectBoth()
        openApp()
        tab("SEARCH")
        search("Finale")
        waitText("Jellyfin Finale", substring = true)
        waitText("Plex Finale", substring = true)
        compose.onNodeWithText("jellyfin").performClick()
        waitText("Jellyfin Finale", substring = true)
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Plex Finale", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("plex").performClick()
        waitText("Plex Finale", substring = true)
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Jellyfin Finale", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("all").performClick()
        waitText("Jellyfin Finale", substring = true)
        waitText("Plex Finale", substring = true)
        search("Mix")
        compose.onNodeWithText("playlists").performClick()
        waitText("Jellyfin Mix")
        waitText("Plex Mix")
        compose.onNodeWithText("jellyfin").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Plex Mix").fetchSemanticsNodes().isEmpty() }
        waitText("Jellyfin Mix")
        compose.onNodeWithText("plex").performClick()
        waitText("Plex Mix")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Jellyfin Mix").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("all").performClick()
        waitText("Jellyfin Mix")
        waitText("Plex Mix")
        assertTrue(searchRequests("Finale").any { it.query("IncludeItemTypes") == "Audio" })
        assertTrue(searchRequests("Mix").any { it.query("IncludeItemTypes") == "Playlist" })
        assertTrue(searchRequests("Mix").any { it.path == "/plex/search" })
    }

    @Test fun searchResultPlayAndAddToQueueSnackbarInsertTheNextTrack() = runBlocking {
        connectBoth()
        openApp()
        tab("SEARCH")
        compose.onNodeWithText("jellyfin").performClick()
        search("Finale")
        waitText("Jellyfin Finale", substring = true)
        compose.onNodeWithContentDescription("Track actions").performClick()
        compose.onNodeWithText("Play", useUnmergedTree = true).performClick()
        awaitPlaying("jf-2")
        // Keep two tracks ahead: appending the search result must not satisfy insert-next.
        val current = engine.get().queue.value.current!!.track
        val remaining = catalog.get().tracks(SourceType.JELLYFIN, "jf-mix-0").filter { it.id != current.id }
        engine.get().setQueue(listOf(current) + remaining)
        awaitPlaying(current.id)
        awaitUntil { engine.get().queue.value.upcoming().map { it.track.id } == remaining.map { it.id } }
        compose.onNodeWithText("plex").performClick()
        waitText("Plex Finale", substring = true)
        compose.onNodeWithContentDescription("Track actions").performClick()
        compose.onNodeWithText("Add to Queue").performClick()
        waitText("Added to queue")
        awaitUntil {
            engine.get().queue.value.entries.size == 4 &&
                engine.get().queue.value.upcoming().map { it.track.id } == listOf("px-2") + remaining.map { it.id }
        }
        tab("NOW_PLAYING")
        compose.onNodeWithContentDescription("Next").performClick()
        awaitPlaying("px-2")
        assertEquals(plex.token, server.awaitRequest { it.path == "/plex/library/parts/px-2/file.wav" }.query("X-Plex-Token"))
    }

    @Test fun searchPlaylistResultStartsItsTracksAndUnknownQueryShowsNoResults() = runBlocking {
        connectBoth()
        openApp()
        tab("SEARCH")
        compose.onNodeWithText("plex").performClick()
        compose.onNodeWithText("playlists").performClick()
        search("Mix")
        waitText("Plex Mix")
        compose.onNodeWithText("Plex Mix").performClick()
        awaitPlaying("px-1")
        assertEquals(3, engine.get().queue.value.entries.size)
        search("NoSuchRecording")
        waitText("No results")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Plex Mix").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun encryptedCredentialsReloadAndStartupRestoresBothProvidersWithoutSettings() = runBlocking {
        // A separate store writes the previous instance's committed encrypted file. The app's Hilt
        // store/auth/engine have not been constructed: startup must read disk, not reuse that flow.
        val previous = SecureConnectionStore(SecurePrefs(Device.context))
        val saved = listOf(
            StoredConnection(SourceType.JELLYFIN, jellyfin.url, token = jellyfin.token, playlistPageSize = 1, jellyfinUserId = "listener"),
            StoredConnection(SourceType.PLEX, plex.url, token = plex.token, playlistPageSize = 1),
        )
        saved.forEach { connection -> previous.update(connection.source) { connection } }
        val reopened = SecureConnectionStore(SecurePrefs(Device.context)).awaitLoaded()
        saved.forEach { assertEquals(it, reopened[it.source]) }
        val raw = java.io.File(Device.context.applicationInfo.dataDir, "shared_prefs/provider_connections.xml").readText()
        assertFalse(raw.contains(jellyfin.token))
        assertFalse(raw.contains(plex.token))
        startup.get().start()
        openApp()
        awaitUntil { startup.get().status.value.message == "Startup complete. 2 provider session(s) restored." }
        assertTrue(startup.get().status.value.warnings.isEmpty())
        assertTrue(auth.get().isSourceConnected(SourceType.JELLYFIN))
        assertTrue(auth.get().isSourceConnected(SourceType.PLEX))
        tab("PLAYLISTS")
        waitText("Jellyfin Mix")
        waitText("Plex Mix")
        assertTrue(server.requestsTo("/jellyfin/Users/listener/Items").any { it.query("Limit") == "1" })
        assertTrue(server.requestsTo("/plex/playlists/all").any { it.query("containerSize") == "1" })
        assertTrue("Restore must not require connection validation", server.requestsTo("/jellyfin/System/Info").isEmpty())
        assertTrue(server.requestsTo("/plex/identity").isEmpty())
    }

    private suspend fun connectionUi(source: SourceType) {
        openApp()
        settings()
        fillConnection(source)
        val index = if (source == SourceType.JELLYFIN) 0 else 1
        compose.onAllNodesWithText("Playlist Page Size")[index].performScrollTo().performTextReplacement("1")
        click("Connect ${label(source)}")
        waitText("${label(source)} connected. Loaded 2 provider playlist(s).")
        compose.onNodeWithTag("${label(source)}_connected", useUnmergedTree = true).assertExists()
        val paths = if (source == SourceType.JELLYFIN) server.requestsTo("/jellyfin/Users/listener/Items") else server.requestsTo("/plex/playlists/all")
        assertEquals(listOf("0", "1"), paths.map { it.query(if (source == SourceType.JELLYFIN) "StartIndex" else "containerStart") })
        assertTrue(paths.all { it.query(if (source == SourceType.JELLYFIN) "Limit" else "containerSize") == "1" })
        compose.onAllNodesWithText("Playlist Page Size")[index].performScrollTo().performTextReplacement("2")
        awaitUntil { connections.get().connections.value[source]?.playlistPageSize == 2 }
        waitText("✓ Saved")
        val reloaded = SecureConnectionStore(SecurePrefs(Device.context)).awaitLoaded()[source]
        assertEquals(2, reloaded?.playlistPageSize)
        tab("PLAYLISTS")
        click("Refresh")
        waitText("Playlist data refreshed successfully!")
        val refreshed = if (source == SourceType.JELLYFIN) server.requestsTo("/jellyfin/Users/listener/Items") else server.requestsTo("/plex/playlists/all")
        assertTrue(refreshed.any { it.query(if (source == SourceType.JELLYFIN) "Limit" else "containerSize") == "2" })
        waitText("${label(source)} Mix")
        waitText("${label(source)} Other")
        assertFalse("Clear-cache precondition: cached playlists exist", catalog.get().playlists.first().isEmpty())
        settings()
        // Disconnect also clears the cache; prove Clear Provider Cache works independently.
        click("Clear Provider Cache")
        waitText("Provider cache cleared.")
        withTimeout(30_000) { catalog.get().playlists.first { it.isEmpty() } }
        compose.onNodeWithTag("${label(source)}_connected", useUnmergedTree = true).assertExists()
        assertTrue(auth.get().isSourceConnected(source))
        tab("PLAYLISTS")
        waitText("No provider playlists loaded")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("${label(source)} Mix").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("${label(source)} Other").fetchSemanticsNodes().isEmpty() }
        settings()
        click("Disconnect")
        waitText("Disconnected ${source.name.lowercase()}.")
        compose.onNodeWithTag("${label(source)}_connected", useUnmergedTree = true).assertDoesNotExist()
        assertFalse(auth.get().isSourceConnected(source))
    }

    private fun connectionErrors(source: SourceType, unauthorized: String, unavailable: String) {
        openApp()
        settings()
        fillConnection(source, "wrong-token")
        click("Connect ${label(source)}")
        waitText(unauthorized)
        compose.onNodeWithTag("${label(source)}_connected", useUnmergedTree = true).assertDoesNotExist()
        fillConnection(source)
        if (source == SourceType.JELLYFIN) jellyfin.online = false else plex.online = false
        click("Connect ${label(source)}")
        waitText(unavailable)
        compose.onNodeWithTag("${label(source)}_connected", useUnmergedTree = true).assertDoesNotExist()
    }

    private suspend fun disconnectGate(source: SourceType) {
        connectBoth()
        val first = catalog.get().tracks(source, if (source == SourceType.JELLYFIN) "jf-mix-0" else "px-mix-0").first()
        val other = if (source == SourceType.JELLYFIN) SourceType.PLEX else SourceType.JELLYFIN
        val second = catalog.get().tracks(other, if (other == SourceType.JELLYFIN) "jf-mix-0" else "px-mix-0").last()
        openApp()
        engine.get().awaitRestored()
        engine.get().setQueue(listOf(first, second))
        awaitPlaying(first.id)
        settings()
        val index = if (source == SourceType.JELLYFIN) 0 else 1
        compose.onAllNodesWithText("Disconnect")[index].performScrollTo().performClick()
        waitText("Disconnected ${source.name.lowercase()}.")
        tab("NOW_PLAYING")
        val reason = "Playback disabled: ${source.name.lowercase()} is not configured/authenticated. Reconnect it in Settings. Next/Previous still works."
        waitText(reason)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Play"))
        compose.onNodeWithContentDescription("Play").assertIsNotEnabled()
        assertFalse(engine.get().player.value.playRequested)
        onMain {
            engine.get().play()
            assertEquals(reason, engine.get().player.value.disabledReason)
            assertFalse(engine.get().player.value.playRequested)
        }
        // Observe every emitted state for a full second, not an already-satisfied precondition.
        val activeState = withTimeoutOrNull(1_000) {
            engine.get().player.first {
                it.status == PlaybackStateType.BUFFERING || it.status == PlaybackStateType.PLAYING
            }
        }
        assertNull("Disconnected provider must never resume or buffer", activeState)
        compose.onNodeWithContentDescription("Next").performClick()
        awaitUntil { engine.get().queue.value.current?.track?.id == second.id && engine.get().player.value.disabledReason == null }
        awaitPlaying(second.id)
        compose.onNodeWithContentDescription("Pause").assertIsEnabled()
    }

    private suspend fun connectBoth() {
        auth.get().connectJellyfin(jellyfin.url, jellyfin.token, 1)
        auth.get().connectPlex(plex.url, plex.token, 1)
        assertTrue(catalog.get().refreshPlaylists().isEmpty())
    }

    private fun openApp() { app = ActivityScenario.launch(MainActivity::class.java) }
    private fun tab(name: String) { compose.onNodeWithTag("tab_$name").performClick() }
    private fun settings() {
        tab("SETTINGS")
        compose.onNodeWithTag("settings_tab_PROVIDERS").performClick()
    }
    private fun label(source: SourceType) = if (source == SourceType.JELLYFIN) "Jellyfin" else "Plex"
    private fun fillConnection(source: SourceType, token: String = if (source == SourceType.JELLYFIN) jellyfin.token else plex.token) {
        val prefix = source.name.lowercase()
        compose.onNodeWithTag("field_${prefix}_url").performScrollTo().performTextReplacement(if (source == SourceType.JELLYFIN) jellyfin.url else plex.url)
        compose.onNodeWithTag("field_${prefix}_token").performScrollTo().performTextReplacement(token)
    }
    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun waitText(text: String, substring: Boolean = false) {
        try {
            compose.waitUntil(30_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Missing text: $text\nRequests: ${server.log.map { it.url }}\n${compose.onRoot(useUnmergedTree = true).printToString()}", failure)
        }
        compose.onAllNodesWithText(text, substring = substring).onFirst().assertExists()
    }
    private fun openPlaylist(name: String) {
        openApp()
        tab("PLAYLISTS")
        waitText(name)
        compose.onNodeWithText(name).performScrollTo().performClick()
        waitText("Tracks: 3")
    }
    private fun search(query: String) {
        compose.onNodeWithText("Search tracks or playlists").performTextReplacement(query)
        compose.onNodeWithTag("search_submit_button").performClick()
    }
    private suspend fun awaitPlaying(id: String) = awaitUntil(message = "$id playing through ExoPlayer") {
        engine.get().queue.value.current?.track?.id == id && engine.get().player.value.status == PlaybackStateType.PLAYING
    }
    private fun trackRequests() = server.log.filter {
        it.path == "/jellyfin/Users/listener/Items" && it.query("ParentId") != null ||
            it.path.startsWith("/plex/playlists/") && it.path.endsWith("/items")
    }
    private fun searchRequests(query: String) = server.log.filter { it.query("searchTerm") == query || it.path == "/plex/search" && it.query("query") == query }
}
