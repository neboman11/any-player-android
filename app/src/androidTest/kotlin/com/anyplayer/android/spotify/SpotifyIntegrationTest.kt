package com.anyplayer.android.spotify

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anyplayer.android.MainActivity
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.auth.ConnectionStore
import com.anyplayer.android.data.auth.PendingSpotifyAuthSession
import com.anyplayer.android.data.auth.SpotifyAuthFlow
import com.anyplayer.android.data.auth.SpotifyAuthSessionStore
import com.anyplayer.android.data.auth.StoredConnection
import com.anyplayer.android.data.sync.AppStartup
import com.anyplayer.android.playback.PlaybackEngine
import com.anyplayer.android.playback.spotify.SharedPrefsSpotifyDevicePreferenceStore
import com.anyplayer.android.testing.Device
import com.anyplayer.android.testing.Device.awaitUntil
import com.anyplayer.android.testing.Device.onMain
import com.anyplayer.android.testing.SpotifyHosts
import com.anyplayer.android.testing.TestServer
import com.anyplayer.android.ui.settings.SpotifyDevicePickerState
import com.anyplayer.android.ui.shell.AnyPlayerShell
import com.anyplayer.android.ui.theme.AnyPlayerTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real app/Room/secure stores/Media3/Compose; only Spotify's remote hosts and browser are substituted. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SpotifyIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val notifications = Device.notificationPermission()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()

    @Inject lateinit var connections: ConnectionStore
    @Inject lateinit var sessions: SpotifyAuthSessionStore
    @Inject lateinit var engineProvider: Provider<PlaybackEngine>
    @Inject lateinit var startup: AppStartup

    private lateinit var server: TestServer
    private lateinit var spotify: FakeSpotify
    private var app: ActivityScenario<MainActivity>? = null
    private var activity: MainActivity? = null
    private var launchIntent: Intent? = null
    private var controller: MediaController? = null
    private val browserUris = CopyOnWriteArrayList<String>()
    private val engine get() = engineProvider.get()

    @Before fun setUp() {
        SpotifyTestClientId.value = SpotifyTestClientId.DEFAULT
        hilt.inject()
        server = TestServer()
        spotify = FakeSpotify(server)
        SpotifyHosts.server = server.httpUrl
    }

    @After fun tearDown() {
        controller?.let { onMain { it.release() } }
        // MainActivity.onNewIntent calls setIntent; Scenario matches lifecycle events by launch Intent.
        onMain { activity?.intent = launchIntent }
        app?.close()
        // Keep the redirect pointed at loopback even after shutdown: IO polling can outlive main idle.
        if (::server.isInitialized) {
            onMain { engine.pause(); engine.setQueue(emptyList(), autoPlay = false) }
            Device.instrumentation.waitForIdleSync()
            server.close()
        }
    }

    @Test fun settingsLinkCreatesPkceSessionWithConfiguredClientId() = runBlocking {
        openApp()
        providers()
        compose.onNodeWithText("Link Spotify Account").performClick()
        compose.waitUntil(30_000) { browserUris.isNotEmpty() }
        val pending = requireNotNull(sessions.read())
        val uri = Uri.parse(browserUris.single())
        assertEquals("accounts.spotify.com", uri.host)
        assertEquals(SpotifyTestClientId.DEFAULT, uri.getQueryParameter("client_id"))
        assertEquals(pending.state, uri.getQueryParameter("state"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(pending.codeVerifier.toByteArray(Charsets.US_ASCII)))
        assertEquals(challenge, uri.getQueryParameter("code_challenge"))
        visible("Continue in browser to link Spotify.")
        assertTrue(server.requestsTo(FakeSpotify.TOKEN_PATH).isEmpty())
    }

    @Test fun accountCallbackExchangesPkceAndConnectsWhileSettingsIsOpen() = runBlocking {
        openApp()
        val pending = beginLink()
        callback("code=accepted-code&state=${pending.state}")
        visible("Spotify connected successfully.")
        compose.onNodeWithTag("Spotify_connected", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("tab_SETTINGS").assertIsSelected()
        val exchange = server.requestsTo(FakeSpotify.TOKEN_PATH, "POST").single()
        val form = FakeSpotify.form(exchange.body)
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("accepted-code", form["code"])
        assertEquals(pending.codeVerifier, form["code_verifier"])
        assertEquals(pending.clientId, form["client_id"])
        assertEquals(SpotifyAuthFlow.REDIRECT_URI, form["redirect_uri"])
        assertNull(sessions.read())
        assertEquals("linked-access", connections.awaitLoaded()[SourceType.SPOTIFY]?.token)
    }

    @Test fun callbackFromNowPlayingOpensSettingsOnProvidersTabWithConnection() = runBlocking {
        val pending = PendingSpotifyAuthSession("cold-state", "cold-verifier".repeat(5),
            SpotifyTestClientId.DEFAULT, SpotifyAuthFlow.REDIRECT_URI)
        sessions.save(pending)
        // Warm callback while Now Playing is shown, without ever opening Settings beforehand
        // (the cold-launch path is coldCallbackLaunchNavigatesToSettingsAndCompletesLinking).
        openApp()
        callback("code=cold-code&state=${pending.state}")
        visible("Spotify connected successfully.")
        compose.onNodeWithTag("settings_tab_PROVIDERS").assertIsSelected()
        compose.onNodeWithTag("tab_SETTINGS").assertIsSelected()
        compose.onNodeWithTag("Spotify_connected", useUnmergedTree = true).assertExists()
        assertEquals("cold-code", FakeSpotify.form(server.requestsTo(FakeSpotify.TOKEN_PATH, "POST").single().body)["code"])
        assertNull(sessions.read())
    }

    @Test fun callbackWithWrongStateShowsErrorAndNeverExchangesTokens() = runBlocking {
        openApp()
        beginLink()
        callback("code=untrusted&state=wrong-state")
        visible("Spotify login failed: Spotify auth state mismatch. Start linking again.")
        assertCallbackRejected()
    }

    @Test fun deniedCallbackShowsErrorAndClearsPendingSession() = runBlocking {
        openApp()
        val pending = beginLink()
        callback("error=access_denied&state=${pending.state}")
        visible("Spotify login failed: Spotify authorization failed: access_denied")
        assertCallbackRejected()
    }

    @Test fun callbackAfterLeavingSettingsIsHandledOnceAndShowsSuccess() = runBlocking {
        openApp()
        val pending = beginLink()
        compose.onNodeWithTag("tab_NOW_PLAYING").performClick()
        callback("code=accepted-code&state=${pending.state}")
        visible("Spotify connected successfully.")
        compose.onNodeWithTag("tab_SETTINGS").assertIsSelected()
        compose.onNodeWithTag("settings_tab_PROVIDERS").assertIsSelected()
        compose.onNodeWithTag("Spotify_connected", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("No pending Spotify auth session", substring = true).assertCountEquals(0)
        assertEquals(1, server.requestsTo(FakeSpotify.TOKEN_PATH, "POST").size)
        assertNull(sessions.read())
    }

    @Test fun freeAccountLinksButExplainsWhyProviderIsIgnored() = runBlocking {
        spotify.premium = false
        openApp()
        val pending = beginLink()
        callback("code=free-account&state=${pending.state}")
        visible("Spotify connected successfully.")
        visible("Spotify account is not Premium. Provider is ignored.")
        awaitDisconnectedMarker()
        assertEquals(false, connections.awaitLoaded()[SourceType.SPOTIFY]?.spotifyPremium)
        assertNotNull(connections.awaitLoaded()[SourceType.SPOTIFY]?.token)
    }

    @Test fun coldCallbackLaunchNavigatesToSettingsAndCompletesLinking() = runBlocking {
        val pending = PendingSpotifyAuthSession("cold-state", "cold-verifier".repeat(5),
            SpotifyTestClientId.DEFAULT, SpotifyAuthFlow.REDIRECT_URI)
        sessions.save(pending)
        app = ActivityScenario.launch(Intent(Device.context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("${SpotifyAuthFlow.REDIRECT_URI}?code=cold-code&state=${pending.state}")))
        visible("Spotify connected successfully.")
        compose.onNodeWithTag("tab_SETTINGS").assertIsSelected()
        compose.onNodeWithTag("settings_tab_PROVIDERS").assertIsSelected()
        compose.onNodeWithTag("Spotify_connected", useUnmergedTree = true).assertExists()
        assertEquals("cold-code", FakeSpotify.form(server.requestsTo(FakeSpotify.TOKEN_PATH, "POST").single().body)["code"])
        assertNull(sessions.read())
    }

    @Test fun startupRefreshesExpiringTokenAndUsesRotationForCatalog() = runBlocking {
        seedConnection(expiresAt = System.currentTimeMillis() + 30_000)
        openApp()
        startup.start()
        awaitUntil(message = "startup catalog with refreshed token") {
            server.requestsTo("${FakeSpotify.API}me/playlists").isNotEmpty()
        }
        compose.onNodeWithTag("tab_PLAYLISTS").performClick()
        visible("Spotify Road Mix")
        val saved = connections.awaitLoaded().getValue(SourceType.SPOTIFY)
        assertEquals("refreshed-1", saved.token)
        assertEquals("rotated-refresh", saved.refreshToken)
        assertTrue(requireNotNull(saved.tokenExpiresAt) > System.currentTimeMillis() + 3_000_000)
        assertEquals(1, server.requestsTo(FakeSpotify.TOKEN_PATH, "POST").size)
        assertEquals("refresh_token", FakeSpotify.form(server.requestsTo(FakeSpotify.TOKEN_PATH).single().body)["grant_type"])
        assertTrue(server.requestsTo("${FakeSpotify.API}me/playlists").all { it.headers["Authorization"] == "Bearer refreshed-1" })
    }

    @Test fun catalogUnauthorizedRefreshesExactlyOnceAndRetriesSearch() = runBlocking {
        seedConnection()
        spotify.rejectedToken = "seed-access"
        openApp()
        search("Spotify")
        visible("1. ${spotify.songs.first().title}")
        val requests = server.requestsTo("${FakeSpotify.API}search", "GET")
        assertEquals(2, requests.size)
        assertEquals(listOf("Bearer seed-access", "Bearer refreshed-1"), requests.map { it.headers["Authorization"] })
        assertEquals(1, server.requestsTo(FakeSpotify.TOKEN_PATH, "POST").size)
        assertEquals("refresh-token", FakeSpotify.form(server.requestsTo(FakeSpotify.TOKEN_PATH).single().body)["refresh_token"])
        assertEquals("refreshed-1", connections.awaitLoaded()[SourceType.SPOTIFY]?.token)
    }

    @Test fun playlistsRefreshAndOpenSpotifyTracksInUi() = runBlocking<Unit> {
        seedConnection()
        openApp()
        compose.onNodeWithTag("tab_PLAYLISTS").performClick()
        compose.onNodeWithText("Refresh").performClick()
        visible("Spotify Road Mix")
        compose.onNodeWithText("Spotify Road Mix").performClick()
        visible("1. ${spotify.songs[0].title}")
        visible("2. ${spotify.songs[1].title}")
        assertEquals("from_token", server.requestsTo("${FakeSpotify.API}playlists/mixtape/tracks").first().query("market"))
        compose.onNodeWithText("Play Playlist").assertIsDisplayed()
    }

    @Test fun searchDisplaysSpotifyTracksAndPlaylistResults() = runBlocking {
        seedConnection()
        openApp()
        search("Road")
        visible("1. ${spotify.songs[0].title}")
        assertEquals("track", server.requestsTo("${FakeSpotify.API}search").last().query("type"))
        compose.onNodeWithText("playlists").performClick()
        compose.onNodeWithTag("search_submit_button").performClick()
        visible("Spotify Road Mix")
        val request = server.requestsTo("${FakeSpotify.API}search").last()
        assertEquals("Road", request.query("q"))
        assertEquals("playlist", request.query("type"))
    }

    @Test fun deviceSelectionPersistsAndTargetsLaterPlayback() = runBlocking {
        seedConnection()
        openApp()
        providers()
        compose.onNodeWithTag("spotify_device_picker").assertTextContains(SpotifyDevicePickerState.AUTOMATIC)
        compose.onNodeWithTag("spotify_device_picker").performClick()
        visible("Studio (Computer)")
        visible("Kitchen (Speaker)")
        // Restricted devices cannot be controlled and are not offered.
        compose.onAllNodesWithText("Restricted TV", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Kitchen (Speaker)").performClick()
        compose.onNodeWithTag("spotify_device_picker").assertTextContains("Kitchen (Speaker)")
        awaitUntil(message = "preferred device persisted") {
            runBlocking { SharedPrefsSpotifyDevicePreferenceStore(Device.context).awaitLoaded()?.id } == "speaker"
        }
        play(spotify.songs)
        val request = server.awaitRequest { it.path == "${FakeSpotify.PLAYER}/play" && it.body.contains("uris") }
        assertEquals("speaker", request.query("device_id"))
        awaitPlaying()
    }

    @Test fun emptyDevicePickerExplainsHowToFindDevices() = runBlocking<Unit> {
        seedConnection()
        spotify.devices = emptyList()
        openApp()
        providers()
        visible("No Spotify devices online. Open Spotify on a device, then Refresh.")
        compose.onNodeWithTag("spotify_device_picker").assertTextContains(SpotifyDevicePickerState.AUTOMATIC)
        assertEquals(1, server.requestsTo("${FakeSpotify.PLAYER}/devices", "GET").size)
    }

    @Test fun spotifyRunStartsOnceWithUrisAndOffsetAndTransportControlsReachApi() = runBlocking {
        seedConnection()
        openApp()
        play(spotify.songs, startIndex = 1)
        awaitPlaying()
        val start = server.requestsTo("${FakeSpotify.PLAYER}/play", "PUT").filter { it.body.contains("uris") }.single()
        val body = JSONObject(start.body)
        // The engine plans from its cursor: already-played prefix is not sent to Spotify.
        assertEquals(spotify.songs.drop(1).map { it.url }, (0 until body.getJSONArray("uris").length()).map { body.getJSONArray("uris").getString(it) })
        assertEquals(0, body.getJSONObject("offset").getInt("position"))
        assertEquals("desktop", start.query("device_id"))
        awaitUntil(message = "Spotify media metadata over IPC") {
            onMain { controller!!.mediaMetadata.title?.toString() } == spotify.songs[1].title
        }

        onMain { controller!!.pause() }
        awaitUntil(message = "paused media session") { !engine.player.value.playRequested && engine.player.value.status == PlaybackStateType.PAUSED }
        server.awaitRequest { it.method == "PUT" && it.path == "${FakeSpotify.PLAYER}/pause" }
        onMain { controller!!.play() }
        awaitPlaying()
        server.awaitRequest {
            it.method == "PUT" && it.path == "${FakeSpotify.PLAYER}/play" &&
                !JSONObject(it.body.ifBlank { "{}" }).has("uris")
        }
        onMain { controller!!.seekTo(17_000) }
        server.awaitRequest { it.path == "${FakeSpotify.PLAYER}/seek" && it.query("position_ms") == "17000" }
        awaitUntil(message = "seek position in engine") { engine.positionMs() in 17_000..21_000 }
        assertEquals(1, server.requestsTo("${FakeSpotify.PLAYER}/play", "PUT").count { it.body.contains("uris") })
    }

    @Test fun playerUnauthorizedRefreshesOnceAndRetriesPause() = runBlocking {
        seedConnection()
        openApp()
        play(spotify.songs)
        awaitPlaying()
        // Reject only pause, not the independently polling me/player endpoint.
        server.route("PUT", "${FakeSpotify.PLAYER}/pause") { request ->
            if (request.headers["Authorization"] == "Bearer seed-access") okhttp3.mockwebserver.MockResponse().setResponseCode(401)
            else okhttp3.mockwebserver.MockResponse().setResponseCode(204)
        }
        onMain { controller!!.pause() }
        awaitUntil(message = "pause retry reached Spotify") { server.requestsTo("${FakeSpotify.PLAYER}/pause", "PUT").size == 2 }
        awaitUntil(message = "pause completed after refresh") { engine.player.value.status == PlaybackStateType.PAUSED }
        assertEquals(1, server.requestsTo(FakeSpotify.TOKEN_PATH, "POST").size)
        assertEquals(listOf("Bearer seed-access", "Bearer refreshed-1"),
            server.requestsTo("${FakeSpotify.PLAYER}/pause", "PUT").map { it.headers["Authorization"] })
    }

    @Test fun spotifySideTrackAdvanceUpdatesQueueMediaSessionAndNowPlaying() = runBlocking {
        seedConnection()
        openApp()
        play(spotify.songs)
        awaitPlaying()
        compose.waitUntil(30_000) { nowPlayingTitle(spotify.songs[0].title).fetchSemanticsNodes().isNotEmpty() }
        nowPlayingTitle(spotify.songs[0].title).onFirst().assertIsDisplayed()
        nowPlayingTitle(spotify.songs[1].title).assertCountEquals(0)
        spotify.advanceTo(spotify.songs[1])
        awaitUntil(message = "Spotify-side queue advance") { engine.queue.value.current?.track?.id == spotify.songs[1].id }
        awaitUntil(message = "Spotify-side media metadata") { onMain { controller!!.mediaMetadata.title?.toString() } == spotify.songs[1].title }
        compose.waitUntil(30_000) {
            nowPlayingTitle(spotify.songs[1].title).fetchSemanticsNodes().isNotEmpty()
        }
        nowPlayingTitle(spotify.songs[1].title).onFirst().assertIsDisplayed()
        assertEquals(1, server.requestsTo("${FakeSpotify.PLAYER}/play", "PUT").count { it.body.contains("uris") })
    }

    @Test fun mixedQueueHandsOffExoToSpotifyAndBackAtRunBoundaries() = runBlocking {
        seedConnection()
        val before = Track("local-before", "Local Before", "Test Artist", durationMs = 3_000,
            source = SourceType.CUSTOM, url = server.audio("/before.wav", 3_000))
        val remote = FakeSpotify.track(4, 30_000).also(spotify::register)
        val after = Track("local-after", "Local After", "Test Artist", durationMs = 60_000,
            source = SourceType.CUSTOM, url = server.audio("/after.wav", 60_000))
        openApp()
        play(listOf(before, remote, after))
        awaitUntil(message = "first Exo track playing") { engine.queue.value.current?.track == before && engine.player.value.status == PlaybackStateType.PLAYING }
        awaitUntil(message = "handoff to Spotify") { engine.queue.value.current?.track == remote && engine.player.value.status == PlaybackStateType.PLAYING }
        val start = server.requestsTo("${FakeSpotify.PLAYER}/play", "PUT").single { it.body.contains("uris") }
        assertEquals(listOf(remote.url), JSONObject(start.body).getJSONArray("uris").let { (0 until it.length()).map(it::getString) })
        // PLAYING is reported optimistically right after the start command; the backend only treats
        // a later stop as natural completion once a poll has seen Spotify playing. Polls are serialized,
        // so a second GET me/player after the start proves the first (playing) snapshot was processed.
        val startIndex = server.log.indexOf(start)
        awaitUntil(message = "Spotify poller observed playback") {
            server.log.drop(startIndex + 1).count { it.method == "GET" && it.path == FakeSpotify.PLAYER } >= 2
        }
        // Spotify reports the run finished (not playing, at the end), as the real poller sees it.
        // Anything the app sends to Spotify from here on is a redundant/stray transport command.
        val handBackLogSize = server.log.size
        spotify.finishCurrent()
        awaitUntil(message = "handoff back to Exo") { engine.queue.value.current?.track == after && engine.player.value.status == PlaybackStateType.PLAYING }
        awaitUntil(message = "last track media metadata") { onMain { controller!!.mediaMetadata.title?.toString() } == after.title }
        // Cursor/metadata change before asynchronous Exo loading; wait for actual audio consumption.
        awaitUntil(message = "Exo consumes final audio") { server.requestsTo("/after.wav").isNotEmpty() }
        awaitUntil(message = "final media item advances audibly") {
            onMain { controller!!.isPlaying && controller!!.currentPosition > 100 }
        }
        assertTrue(server.requestsTo("/before.wav").isNotEmpty())
        assertTrue(server.requestsTo("/after.wav").isNotEmpty())
        // Absence check: keep watching across more than one paused-poll interval (5 s) of Exo playback.
        val observedUntil = android.os.SystemClock.elapsedRealtime() + 6_000
        while (android.os.SystemClock.elapsedRealtime() < observedUntil) {
            val stray = server.log.drop(handBackLogSize).filter {
                it.method == "PUT" && it.path in setOf("${FakeSpotify.PLAYER}/play", "${FakeSpotify.PLAYER}/pause")
            }
            assertTrue("Spotify transport command after hand-back: ${stray.map { it.method + " " + it.path }}", stray.isEmpty())
            kotlinx.coroutines.delay(100)
        }
    }

    @Test fun disconnectedSpotifyShowsDisabledReasonAndDoesNotStartPlayback() = runBlocking {
        connections.awaitLoaded()
        openApp()
        engine.awaitRestored()
        onMain { engine.setQueue(spotify.songs, autoPlay = true) }
        val reason = "Playback disabled: spotify is not configured/authenticated. Reconnect it in Settings. Next/Previous still works."
        visible(reason)
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Play"))
        compose.onNodeWithContentDescription("Play").assertIsNotEnabled()
        assertEquals(reason, engine.player.value.disabledReason)
        assertTrue(server.requestsTo("${FakeSpotify.PLAYER}/play", "PUT").isEmpty())
    }

    @Test fun deviceShuffleIsDisabledAndVolumeAndRepeatAreAppliedThroughEngine() = runBlocking {
        seedConnection()
        spotify.enableDeviceShuffle()
        openApp()
        play(spotify.songs)
        awaitPlaying()
        server.awaitRequest { it.path == "${FakeSpotify.PLAYER}/shuffle" && it.query("state") == "false" }
        onMain { engine.setVolume(37); engine.setRepeat(RepeatMode.ONE) }
        // Repeat is sent immediately, volume after a debounce: wait for both without assuming order.
        awaitUntil(message = "Spotify volume and repeat commands") {
            server.requestsTo("${FakeSpotify.PLAYER}/volume", "PUT").any { it.query("volume_percent") == "37" } &&
                server.requestsTo("${FakeSpotify.PLAYER}/repeat", "PUT").any { it.query("state") == "track" }
        }
        val volume = server.requestsTo("${FakeSpotify.PLAYER}/volume", "PUT").first { it.query("volume_percent") == "37" }
        assertEquals("desktop", volume.query("device_id"))
    }

    private fun openApp() {
        app = ActivityScenario.launch(MainActivity::class.java)
        app!!.onActivity { launched ->
            activity = launched
            launchIntent = Intent(launched.intent)
            // Keep production shell/ViewModels; substitute only the external browser.
            launched.setContent {
                AnyPlayerTheme {
                    CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) { browserUris += uri }
                    }) { AnyPlayerShell() }
                }
            }
        }
        compose.onNodeWithTag("tab_SETTINGS").assertExists()
    }

    private fun providers() {
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithTag("settings_tab_PROVIDERS").performClick()
    }

    private suspend fun beginLink(): PendingSpotifyAuthSession {
        providers()
        compose.onNodeWithText("Link Spotify Account").performClick()
        compose.waitUntil(30_000) { browserUris.isNotEmpty() }
        return requireNotNull(sessions.read())
    }

    private fun callback(query: String) {
        Device.context.startActivity(Intent(Device.context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("${SpotifyAuthFlow.REDIRECT_URI}?$query"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        Device.instrumentation.waitForIdleSync()
    }

    private suspend fun assertCallbackRejected() {
        assertNull(sessions.read())
        assertNull(connections.awaitLoaded()[SourceType.SPOTIFY])
        assertTrue(server.requestsTo(FakeSpotify.TOKEN_PATH).isEmpty())
        awaitDisconnectedMarker()
    }

    private fun awaitDisconnectedMarker() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("Spotify_connected", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("Spotify_connected", useUnmergedTree = true).assertDoesNotExist()
    }

    // Up Next/history titles live inside clickable queue rows; the header title does not.
    private fun nowPlayingTitle(title: String) = compose.onAllNodes(
        hasText(title) and !hasClickAction() and !hasAnyAncestor(hasClickAction()),
        useUnmergedTree = true,
    )

    private suspend fun seedConnection(expiresAt: Long = System.currentTimeMillis() + 3_600_000) {
        connections.update(SourceType.SPOTIFY) {
            StoredConnection(SourceType.SPOTIFY, username = "Spotify Listener", token = "seed-access",
                refreshToken = "refresh-token", tokenExpiresAt = expiresAt, spotifyPremium = true, playbackReady = true)
        }
    }

    private suspend fun play(tracks: List<Track>, startIndex: Int = 0) {
        engine.awaitRestored()
        controller = Device.connectController()
        onMain { engine.setQueue(tracks, startIndex = startIndex, autoPlay = true) }
    }

    private suspend fun awaitPlaying() = awaitUntil(message = "Spotify playing (not just buffering)") {
        engine.player.value.status == PlaybackStateType.PLAYING && engine.player.value.playRequested
    }

    private fun search(query: String) {
        compose.onNodeWithTag("tab_SEARCH").performClick()
        compose.onNodeWithText("spotify").performClick()
        compose.onNodeWithText("Search tracks or playlists").performTextInput(query)
        compose.onNodeWithTag("search_submit_button").performClick()
    }

    private fun visible(text: String) {
        try {
            compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Missing '$text'. HTTP: ${server.log.map { it.method + " " + it.path }}\n${compose.onRoot().printToString()}", failure)
        }
        compose.onAllNodesWithText(text).onFirst().assertIsDisplayed()
    }
}
