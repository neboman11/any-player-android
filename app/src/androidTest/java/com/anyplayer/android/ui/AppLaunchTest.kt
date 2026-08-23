package com.anyplayer.android.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * stored connections), so the app renders its normal offline/disconnected state without
 * touching real network or Rust FFI.
 */
@HiltAndroidTest
@RunWith(JUnit4::class)
class AppLaunchTest {
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
    }

    @Test
    fun launchesToNowPlayingTabByDefault() {
        composeRule.onNodeWithText("Any Player").assertExists()
        composeRule.onNodeWithTag("tab_NOW_PLAYING").assertExists()
    }

    @Test
    fun canNavigateToEveryTab() {
        composeRule.onNodeWithTag("tab_PLAYLISTS").performClick()
        composeRule.onNodeWithText("New standard playlist").assertExists()

        composeRule.onNodeWithTag("tab_SEARCH").performClick()
        composeRule.onNodeWithText("Search tracks or playlists").assertExists()

        composeRule.onNodeWithTag("tab_SETTINGS").performClick()
        composeRule.onNodeWithText("General").assertExists()

        composeRule.onNodeWithTag("tab_NOW_PLAYING").performClick()
        composeRule.onNodeWithTag("tab_NOW_PLAYING").assertExists()
    }
}
