package com.anyplayer.android.feature.djfiller

import android.content.Context
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.djfiller.metadata.WikipediaFactClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DjFillerContentTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `prompt requires sourced song trivia instead of vibe descriptions`() {
        val generator = DjScriptGenerator(mock<Context>(), mock())
        val prompt = generator.privatePrompt(
            track = track(title = "The Song", artist = "The Artist"),
            fact = "It was released as a single in 1982."
        )

        assertTrue(prompt.contains("Use only this sourced song research"))
        assertTrue(prompt.contains("Do not describe the song's vibe"))
    }

    @Test
    fun `generation researches the upcoming song before writing its announcement`() = runTest {
        val scriptGenerator = mock<DjScriptGenerator>()
        val voiceSynthesizer = mock<DjVoiceSynthesizer>()
        val factClient = mock<WikipediaFactClient>()
        val audioCache = mock<DjFillerAudioCache>()
        val scheduler = DjFillerScheduler(
            scriptGenerator,
            voiceSynthesizer,
            factClient,
            audioCache,
            mock(),
            testDispatcher
        )
        val currentTrack = track(id = "current", title = "Current Song", artist = "Current Artist")
        val upcomingTrack = track(id = "upcoming", title = "The Song", artist = "The Artist")
        val status = PlaybackStatus(
            state = PlaybackStateType.PLAYING,
            shuffle = false,
            repeatMode = RepeatMode.OFF,
            volume = 100,
            currentTrack = currentTrack,
            position = 0,
            duration = 200_000,
            queue = listOf(currentTrack, upcomingTrack),
            orderedQueue = listOf(currentTrack, upcomingTrack)
        )

        whenever(voiceSynthesizer.isAvailable()).thenReturn(true)
        whenever(factClient.fetchArtistFact(any())).thenReturn("It was released as a single in 1982.")
        whenever(scriptGenerator.generateScript(any(), anyOrNull())).thenReturn("Up next.")
        whenever(audioCache.newOutputFile()).thenReturn(File("unused.wav"))
        whenever(voiceSynthesizer.synthesizeToFile(any(), any())).thenReturn(true)

        scheduler.startGenerationFor(status, currentTrack)
        advanceUntilIdle()

        verify(factClient).fetchArtistFact("The Song (The Artist song)")
    }

    private fun track(
        id: String = "track",
        title: String,
        artist: String
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        source = SourceType.JELLYFIN
    )

    private fun DjScriptGenerator.privatePrompt(track: Track, fact: String): String {
        val method = DjScriptGenerator::class.java.getDeclaredMethod(
            "buildPrompt",
            Track::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(this, track, fact) as String
    }

    private fun DjFillerScheduler.startGenerationFor(status: PlaybackStatus, track: Track) {
        val method = DjFillerScheduler::class.java.getDeclaredMethod(
            "startGenerationFor",
            PlaybackStatus::class.java,
            Track::class.java
        )
        method.isAccessible = true
        method.invoke(this, status, track)
    }
}
