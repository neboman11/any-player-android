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
 * No providers connected in these tests, so [com.anyplayer.android.feature.providers.ProviderCatalogRepository.search]
 * always resolves with an empty result (no stored credentials to search against) —
 * these tests only verify the search screen stays stable through query/filter/search
 * interactions, not real result rendering.
 */
@HiltAndroidTest
@RunWith(JUnit4::class)
class SearchScreenTest {
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
        composeRule.onNodeWithTag("tab_SEARCH").performClick()
    }

    @Test
    fun showsSearchControlsAndFilters() {
        composeRule.onNodeWithText("Search tracks or playlists").assertExists()
        composeRule.onNodeWithText("all").assertExists()
        composeRule.onNodeWithText("jellyfin").assertExists()
        composeRule.onNodeWithText("plex").assertExists()
        composeRule.onNodeWithText("spotify").assertExists()
        composeRule.onNodeWithText("tracks").assertExists()
        composeRule.onNodeWithText("playlists").assertExists()
        composeRule.onNodeWithTag("search_submit_button").assertExists()
        composeRule.onNodeWithText("Title").assertExists()
    }

    @Test
    fun canRunTrackSearchWithNoProvidersConnected() {
        composeRule.onNodeWithText("Search tracks or playlists").performTextInput("abc")
        composeRule.onNodeWithTag("search_submit_button").performClick()

        composeRule.onNodeWithText("Title").assertExists()
    }

    @Test
    fun canSwitchToPlaylistSearchType() {
        composeRule.onNodeWithText("playlists").performClick()

        composeRule.onNodeWithText("Title").assertDoesNotExist()

        composeRule.onNodeWithTag("search_submit_button").performClick()

        composeRule.onNodeWithText("Search tracks or playlists").assertExists()
    }

    @Test
    fun canFilterBySourceTypeAndSearch() {
        composeRule.onNodeWithText("jellyfin").performClick()
        composeRule.onNodeWithText("Search tracks or playlists").performTextInput("abc")
        composeRule.onNodeWithTag("search_submit_button").performClick()

        composeRule.onNodeWithText("Title").assertExists()
    }
}
