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
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files

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
        whenever(audioCache.newOutputFile()).thenReturn(
            Files.createTempFile("dj-filler", ".wav").toFile().apply { writeBytes(byteArrayOf(1)) }
        )
        whenever(audioCache.save(any(), any())).thenAnswer { it.arguments[1] as File }
        whenever(voiceSynthesizer.synthesizeToFile(any(), any())).thenReturn(true)

        scheduler.startGenerationFor(status, currentTrack)
        advanceUntilIdle()

        verify(factClient).fetchArtistFact("The Song (The Artist song)")
    }

    @Test
    fun `enabled scheduler starts generation for the next track immediately`() = runTest {
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
        whenever(audioCache.newOutputFile()).thenReturn(
            Files.createTempFile("dj-filler", ".wav").toFile().apply { writeBytes(byteArrayOf(1)) }
        )
        whenever(audioCache.save(any(), any())).thenAnswer { it.arguments[1] as File }
        whenever(voiceSynthesizer.synthesizeToFile(any(), any())).thenReturn(true)

        scheduler.setEnabled(true)
        scheduler.onStatusUpdated(status)
        advanceUntilIdle()

        verify(scriptGenerator).generateScript(upcomingTrack, "It was released as a single in 1982.")
    }

    @Test
    fun `cache is not restored when current track is absent from the queue`() {
        val audioCache = mock<DjFillerAudioCache>()
        val scheduler = DjFillerScheduler(mock(), mock(), mock(), audioCache, mock(), testDispatcher)
        val queuedTrack = track(id = "queued", title = "Queued", artist = "Artist")
        val missingTrack = track(id = "missing", title = "Missing", artist = "Artist")

        scheduler.setEnabled(true)
        scheduler.onStatusUpdated(
            PlaybackStatus(
                state = PlaybackStateType.PLAYING,
                shuffle = false,
                repeatMode = RepeatMode.OFF,
                volume = 100,
                currentTrack = missingTrack,
                position = 0,
                duration = 200_000,
                queue = listOf(queuedTrack),
                orderedQueue = listOf(queuedTrack)
            )
        )

        verify(audioCache, never()).load(any())
    }

    @Test
    fun `disabling during local generation does not insert a filler`() = runTest {
        val scriptGenerator = mock<DjScriptGenerator>()
        val voiceSynthesizer = mock<DjVoiceSynthesizer>()
        val factClient = mock<WikipediaFactClient>()
        val audioCache = mock<DjFillerAudioCache>()
        val interstitialPlayer = mock<DjInterstitialPlayer>()
        val scheduler = DjFillerScheduler(
            scriptGenerator,
            voiceSynthesizer,
            factClient,
            audioCache,
            interstitialPlayer,
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
        val outputFile = Files.createTempFile("dj-filler", ".wav").toFile().apply { writeBytes(byteArrayOf(1)) }
        whenever(voiceSynthesizer.isAvailable()).thenReturn(true)
        whenever(factClient.fetchArtistFact(any())).thenReturn("It was released as a single in 1982.")
        whenever(scriptGenerator.generateScript(any(), anyOrNull())).thenReturn("Up next.")
        whenever(audioCache.newOutputFile()).thenReturn(outputFile)
        whenever(audioCache.save(any(), any())).thenAnswer {
            scheduler.setEnabled(false)
            outputFile
        }
        whenever(voiceSynthesizer.synthesizeToFile(any(), any())).thenReturn(true)
        scheduler.configureLocalModeProvider { true }

        scheduler.setEnabled(true)
        scheduler.onStatusUpdated(status)
        advanceUntilIdle()

        verify(interstitialPlayer, never()).insertLocal(any())
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
