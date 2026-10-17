package com.anyplayer.android.dj

import android.content.ComponentCallbacks2
import android.content.Context
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.settings.*
import com.anyplayer.android.playback.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class DjLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private class PlayerPrefs : PlayerSettingsStore {
        override val settings = MutableStateFlow(PlayerSettings(aiDjEnabled = true))
        override suspend fun awaitLoaded() = settings.value
        override fun update(transform: (PlayerSettings) -> PlayerSettings) { settings.value = transform(settings.value) }
    }
    private class DjPrefs : DjSettingsStore {
        override val settings = MutableStateFlow(DjSettings(remoteGeneration = false))
        var failWrites = false
        override suspend fun awaitLoaded() = settings.value
        override suspend fun update(transform: (DjSettings) -> DjSettings) {
            if (failWrites) throw IOException("prefs disk full")
            settings.value = transform(settings.value)
        }
    }
    @Test fun `hiding UI keeps native models while background and low memory release them`() = runTest {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.root)
        val engine = mock(PlaybackEngine::class.java)
        val events = MutableSharedFlow<EngineEvent>()
        `when`(engine.queue).thenReturn(MutableStateFlow(QueueState()))
        `when`(engine.player).thenReturn(MutableStateFlow(PlayerState()))
        `when`(engine.events).thenReturn(events)
        val local = mock(OnDeviceBreakGenerator::class.java)
        val coordinator = DjCoordinator(context, engine, PlayerPrefs(), DjPrefs(), mock(PassageRepository::class.java),
            mock(RemoteBreakGenerator::class.java), local, DjDiagnostics(), backgroundScope)
        events.subscriptionCount.first { it > 0 }
        runCurrent()
        coordinator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        runCurrent()
        verifyNoInteractions(local)
        coordinator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        runCurrent()
        verify(local, times(1)).release()
        coordinator.onLowMemory()
        runCurrent()
        verify(local, times(2)).release()
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun `initialization and persisted download selection failures are logged not uncaught`() = runTest {
        val models = mock(ArtifactStore::class.java) as ArtifactStore<ModelDescriptor>
        val voices = mock(ArtifactStore::class.java) as ArtifactStore<VoiceDescriptor>
        val descriptor = ModelDescriptor("model", "Model", "1", 1, "0".repeat(64))
        `when`(models.catalog).thenReturn(MutableStateFlow(ArtifactCatalogState(items = listOf(descriptor), defaultId = "model")))
        `when`(voices.catalog).thenReturn(MutableStateFlow(ArtifactCatalogState()))
        `when`(models.download).thenReturn(MutableStateFlow(ArtifactDownloadState.NotDownloaded))
        `when`(voices.download).thenReturn(MutableStateFlow(ArtifactDownloadState.NotDownloaded))
        `when`(models.downloaded).thenReturn(MutableStateFlow(emptyList()))
        `when`(voices.downloaded).thenReturn(MutableStateFlow(emptyList()))
        doAnswer { throw IOException("migration disk full") }.`when`(models).initialize()
        doAnswer { throw IllegalStateException("incomplete voice") }.`when`(voices).initialize()
        val coordinator = mock(DjCoordinator::class.java)
        `when`(coordinator.songsAway).thenReturn(MutableStateFlow(null))
        `when`(coordinator.preparationStatus).thenReturn(MutableStateFlow(PreparationStatus.NOT_STARTED))
        val prefs = DjPrefs().apply { failWrites = true }
        val logs = DjDiagnostics()
        val controller = DefaultDjController(PlayerPrefs(), prefs, coordinator, models, voices,
            mock(OnDeviceBreakGenerator::class.java), logs, backgroundScope)
        runCurrent()
        assertEquals(2, logs.entries.value.size)
        controller.downloadModel()
        runCurrent()
        assertEquals(3, logs.entries.value.size)
        assertTrue(logs.entries.value.all { it.message.startsWith("DJ action failed") })
        verify(models, never()).downloadSelected()
    }
}
