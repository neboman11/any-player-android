package com.anyplayer.android.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * stored connections), so playback starts idle with an empty queue. This only verifies
 * the idle Now Playing screen renders its controls and empty state, not real playback.
 */
@HiltAndroidTest
@RunWith(JUnit4::class)
class NowPlayingScreenTest {
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
    fun showsIdleStateAndEmptyQueueByDefault() {
        composeRule.onNodeWithText("Idle").assertExists()
        composeRule.onAllNodesWithText("—").assertCountEquals(2)
        composeRule.onNodeWithText("No queue loaded").assertExists()
    }

    @Test
    fun showsTransportControls() {
        composeRule.onNodeWithContentDescription("Previous").assertExists()
        composeRule.onNodeWithContentDescription("Play").assertExists()
        composeRule.onNodeWithContentDescription("Next").assertExists()
    }

    @Test
    fun showsVolumeControls() {
        composeRule.onNodeWithContentDescription("Volume down").assertExists()
        composeRule.onNodeWithContentDescription("Volume up").assertExists()
    }

    @Test
    fun showsShuffleAndRepeatModeChips() {
        composeRule.onNodeWithText("Shuffle").assertExists()
        composeRule.onNodeWithText("Off").assertExists()
        composeRule.onNodeWithText("All").assertExists()
        composeRule.onNodeWithText("One").assertExists()
    }

    @Test
    fun canToggleShuffleChip() {
        composeRule.onNodeWithText("Shuffle").performClick()
    }

    @Test
    fun canSelectRepeatModeChip() {
        composeRule.onNodeWithText("All").performClick()
    }
}
