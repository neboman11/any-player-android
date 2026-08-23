package com.anyplayer.android.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * No providers connected in these tests (fresh in-memory DB + fake auth repo with no
 * stored connections), so only the local custom-playlist CRUD flow is exercised.
 * Play actions are deliberately not clicked here since they'd drive real playback/Rust
 * FFI, out of scope for this UI-only coverage.
 */
@HiltAndroidTest
@RunWith(JUnit4::class)
class PlaylistsScreenTest {
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
        composeRule.onNodeWithTag("tab_PLAYLISTS").performClick()
    }

    @Test
    fun showsEmptyStateAndCreateControls() {
        composeRule.onNodeWithText("Provider playlists").assertExists()
        composeRule.onNodeWithText("No provider playlists loaded").assertExists()
        composeRule.onNodeWithText("Local custom playlists").assertExists()
        composeRule.onNodeWithTag("field_new_standard_playlist_name").assertExists()
        composeRule.onNodeWithTag("field_new_union_playlist_name").assertExists()
    }

    @Test
    fun canCreateStandardPlaylistAndViewThenCloseDetails() {
        composeRule.onNodeWithTag("field_new_standard_playlist_name").performTextInput("My Playlist")
        composeRule.onNodeWithTag("create_standard_playlist_button").performClick()

        composeRule.onNodeWithText("My Playlist").assertExists()
        composeRule.onNodeWithText("standard").assertExists()

        composeRule.onNodeWithText("My Playlist").performClick()

        composeRule.onNodeWithText("My Playlist (standard)").assertExists()
        composeRule.onNodeWithText("Tracks: 0").assertExists()
        composeRule.onNodeWithText("No tracks found for this playlist").assertExists()

        composeRule.onNodeWithText("Back").performClick()

        composeRule.onNodeWithTag("field_new_standard_playlist_name").assertExists()
    }

    @Test
    fun canCreateUnionPlaylist() {
        composeRule.onNodeWithTag("field_new_union_playlist_name").performTextInput("My Union")
        composeRule.onNodeWithTag("create_union_playlist_button").performClick()

        composeRule.onNodeWithText("My Union").assertExists()
        composeRule.onNodeWithText("union").assertExists()
    }

    @Test
    fun canDeleteCustomPlaylist() {
        composeRule.onNodeWithTag("field_new_standard_playlist_name").performTextInput("ToDelete")
        composeRule.onNodeWithTag("create_standard_playlist_button").performClick()
        composeRule.onNodeWithText("ToDelete").assertExists()

        composeRule.onNodeWithContentDescription("Delete ToDelete").performClick()

        composeRule.onNodeWithText("ToDelete").assertDoesNotExist()
    }
}
