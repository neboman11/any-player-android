package com.anyplayer.android.dj

import android.content.Context
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.settings.DjSettingsStore
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class NativeFailureTest {
    private fun nativeTest(block: suspend (NativeDjThread) -> Unit) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val native = mock(NativeDjThread::class.java)
            `when`(native.dispatcher).thenReturn(dispatcher)
            runBlocking { block(native) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun `native linkage errors become diagnostic failures but cancellation propagates`() = nativeTest { native ->
        val models = mock(ArtifactStore::class.java) as ArtifactStore<ModelDescriptor>
        `when`(models.activeFileOrNull()).thenThrow(UnsatisfiedLinkError("missing runtime"))
        val diagnostics = DjDiagnostics()
        val scripts = ScriptGenerator(mock(Context::class.java), models, native, diagnostics)
        val track = Track("id", "Song", "Artist", source = SourceType.CUSTOM)
        assertNull(scripts.generateScript(track, Passages(emptyList())))
        assertTrue(diagnostics.entries.value.single().message.contains("UnsatisfiedLinkError"))
        doAnswer { throw CancellationException("cancelled") }.`when`(models).activeFileOrNull()
        try { scripts.generateScript(track, Passages(emptyList())); fail("Cancellation swallowed") }
        catch (_: CancellationException) { assertEquals(1, diagnostics.entries.value.size) }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun `missing sherpa native decoder is contained and logged`() = nativeTest { native ->
        val diagnostics = DjDiagnostics()
        val voices = VoiceSynthesizer(mock(Context::class.java), mock(ArtifactStore::class.java) as ArtifactStore<VoiceDescriptor>,
            mock(DjSettingsStore::class.java), 2, native, diagnostics)
        assertFalse(voices.normalizeWaveFile(File("missing.wav")))
        assertTrue(diagnostics.entries.value.single().message.startsWith("Server audio could not be decoded"))
    }
}
