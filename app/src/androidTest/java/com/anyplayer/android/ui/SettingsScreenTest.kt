package com.anyplayer.android.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.anyplayer.android.MainActivity
import com.anyplayer.android.fakes.FakeProviderAuthRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * No providers connected at start (fresh in-memory DB + fake auth repo with no stored
 * connections). [FakeProviderAuthRepository.connect] succeeds unless a test sets
 * [FakeProviderAuthRepository.nextConnectError].
 */
@HiltAndroidTest
@RunWith(JUnit4::class)
class SettingsScreenTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var fakeProviderAuthRepository: FakeProviderAuthRepository

    @Before
    fun init() {
        hiltRule.inject()
        fakeProviderAuthRepository.reset()
        composeRule.onNodeWithTag("tab_SETTINGS").performClick()
    }

    @Test
    fun generalTabShowsSyncPlaybackAndDataSections() {
        composeRule.onNodeWithText("Sync").assertExists()
        composeRule.onNodeWithText("Playback").assertExists()
        composeRule.onNodeWithText("Data").assertExists()
        composeRule.onNodeWithText("Normalize Audio Across Providers").assertExists()
        composeRule.onNodeWithText("Strict Normalization").assertExists()
        composeRule.onNodeWithText("Clear Provider Cache").assertExists()
        composeRule.onNodeWithText("Choose what you'd like to do:").assertExists()
    }

    @Test
    fun canToggleSyncFilterChips() {
        composeRule.onNodeWithText("app_state").performClick()
        composeRule.onNodeWithText("playlists").performClick()
    }

    @Test
    fun canConnectJellyfinFromCredentialFields() {
        composeRule.onNodeWithTag("settings_tab_JELLYFIN").performClick()
        composeRule.onNodeWithTag("field_jellyfin_url").assertExists()

        composeRule.onNodeWithTag("field_jellyfin_url").performTextInput("http://jellyfin.local:8096")
        composeRule.onNodeWithTag("field_jellyfin_token").performTextInput("test-jellyfin-token")

        composeRule.onNodeWithText("Connect Jellyfin").performClick()

        composeRule.onNodeWithText("Disconnect").assertExists()
    }

    @Test
    fun canConnectPlexFromCredentialFields() {
        composeRule.onNodeWithTag("settings_tab_PLEX").performClick()
        composeRule.onNodeWithTag("field_plex_url").assertExists()

        composeRule.onNodeWithTag("field_plex_url").performTextInput("http://plex.local:32400")
        composeRule.onNodeWithTag("field_plex_token").performTextInput("test-plex-token")

        composeRule.onNodeWithText("Connect Plex").performClick()

        composeRule.onNodeWithText("Disconnect").assertExists()
    }

    @Test
    fun jellyfinConnectFailureShowsError() {
        fakeProviderAuthRepository.nextConnectError = RuntimeException("boom")

        composeRule.onNodeWithTag("settings_tab_JELLYFIN").performClick()
        composeRule.onNodeWithTag("field_jellyfin_url").performTextInput("http://jellyfin.local:8096")
        composeRule.onNodeWithTag("field_jellyfin_token").performTextInput("test-jellyfin-token")
        composeRule.onNodeWithText("Connect Jellyfin").performClick()

        composeRule.onNodeWithText("Jellyfin connection failed: boom").assertExists()
    }
}
