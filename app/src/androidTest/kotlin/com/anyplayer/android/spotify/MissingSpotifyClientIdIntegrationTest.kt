package com.anyplayer.android.spotify

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anyplayer.android.MainActivity
import com.anyplayer.android.data.auth.SpotifyAuthSessionStore
import com.anyplayer.android.testing.Device
import com.anyplayer.android.testing.SpotifyHosts
import com.anyplayer.android.testing.TestServer
import com.anyplayer.android.ui.settings.ProvidersSettingsViewModel
import com.anyplayer.android.ui.shell.AnyPlayerShell
import com.anyplayer.android.ui.theme.AnyPlayerTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Separate component: client ID must be blank before constructor-injected auth objects are created. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MissingSpotifyClientIdIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val notifications = Device.notificationPermission()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()

    @Inject lateinit var sessions: SpotifyAuthSessionStore
    private lateinit var server: TestServer
    private var app: ActivityScenario<MainActivity>? = null
    private val browserUris = CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        SpotifyTestClientId.value = ""
        hilt.inject()
        server = TestServer()
        FakeSpotify(server)
        SpotifyHosts.server = server.httpUrl
    }

    @After fun tearDown() {
        app?.close()
        if (::server.isInitialized) server.close()
        // Never restore real Spotify hosts while this process can still have IO work in flight.
        SpotifyTestClientId.value = SpotifyTestClientId.DEFAULT
    }

    @Test fun settingsLinkExplainsMissingClientIdWithoutOpeningBrowserOrExchangingTokens() = runBlocking {
        app = ActivityScenario.launch(MainActivity::class.java)
        app!!.onActivity { activity ->
            activity.setContent {
                AnyPlayerTheme {
                    CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) { browserUris += uri }
                    }) { AnyPlayerShell() }
                }
            }
        }
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithTag("settings_tab_PROVIDERS").performClick()
        compose.onNodeWithText("Link Spotify Account").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(ProvidersSettingsViewModel.MISSING_CLIENT_ID).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(ProvidersSettingsViewModel.MISSING_CLIENT_ID).assertIsDisplayed()
        assertNull(sessions.read())
        assertTrue(browserUris.isEmpty())
        assertTrue(server.requestsTo(FakeSpotify.TOKEN_PATH).isEmpty())
    }
}
