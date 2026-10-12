package com.anyplayer.android.feature.djfiller

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.djfiller.metadata.DjPassage
import com.anyplayer.android.feature.djfiller.metadata.DjPassages
import com.anyplayer.android.feature.sync.SyncPreferences
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DjRemoteBreakGeneratorTest {
    @Test
    fun `generate succeeds for 200 and writes the WAV file`() = runTest {
        val output = File.createTempFile("dj-break", ".wav")
        val prefs = preferences()
        val voice = mock<DjVoiceSynthesizer>()
        whenever(voice.normalizeWaveFile(any())).thenReturn(true)
        val generator = DjRemoteBreakGenerator(successClient(), Json, prefs, voice)

        try {
            val track = Track("track-id", "Song Title", "Artist", source = SourceType.SPOTIFY)
            val passages = DjPassages(listOf(DjPassage(11L, "Quote", "genius", "https://example.com")))

            assertTrue(generator.generate(track, passages, output))
            assertTrue(output.readText().startsWith("server-audio"))
            verify(voice).normalizeWaveFile(output)
        } finally {
            output.delete()
        }
    }

    @Test
    fun `generate treats a 204 fact-check rejection as a failed generation`() = runTest {
        val prefs = preferences()
        val voice = mock<DjVoiceSynthesizer>()
        val generator = DjRemoteBreakGenerator(failureClient(204), Json, prefs, voice)

        val track = Track("track-id", "Song Title", "Artist", source = SourceType.SPOTIFY)
        val passages = DjPassages(listOf(DjPassage(11L, "Quote", "genius", "https://example.com")))

        assertFalse(generator.generate(track, passages, File.createTempFile("dj-break", ".wav").also { it.delete() }))
        verify(voice, org.mockito.kotlin.never()).normalizeWaveFile(any())
    }

    @Test
    fun `generate treats non-2xx responses as failures`() = runTest {
        val prefs = preferences()
        val voice = mock<DjVoiceSynthesizer>()
        val generator = DjRemoteBreakGenerator(failureClient(500), Json, prefs, voice)

        val track = Track("track-id", "Song Title", "Artist", source = SourceType.SPOTIFY)
        val passages = DjPassages(listOf(DjPassage(11L, "Quote", "genius", "https://example.com")))

        assertFalse(generator.generate(track, passages, File.createTempFile("dj-break", ".wav").also { it.delete() }))
        verify(voice, org.mockito.kotlin.never()).normalizeWaveFile(any())
    }

    private fun preferences(): SyncPreferencesStore = mock<SyncPreferencesStore>().also {
        whenever(it.read()).thenReturn(SyncPreferences(serverTarget = "https://sync.example", authToken = "token"))
        whenever(it.selectedDjVoiceId()).thenReturn("voice-1")
        whenever(it.djRemoteGeneration()).thenReturn(true)
    }

    private fun successClient(): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        assertTrue(request.header("Authorization")?.isNotBlank() == true)
        assertEquals("POST", request.method)
        assertEquals("/v1/dj-breaks", request.url.encodedPath)
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("server-audio".toByteArray().toResponseBody("audio/wav".toMediaType()))
            .build()
    }.build()

    private fun failureClient(code: Int): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 204) "No Content" else "Server Error")
            .body("".toResponseBody("text/plain".toMediaType()))
            .build()
    }.build()
}
