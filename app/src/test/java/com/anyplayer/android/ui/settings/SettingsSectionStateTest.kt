package com.anyplayer.android.ui.settings

import com.anyplayer.android.feature.djfiller.DjVoiceCatalog
import com.anyplayer.android.feature.djfiller.DjVoiceDescriptor
import com.anyplayer.android.feature.djfiller.DjVoiceState
import com.anyplayer.android.feature.djfiller.model.DjModelDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionStateTest {
    private val baritone = DjVoiceDescriptor("baritone-id", "Baritone", "v1", 1, "a".repeat(64))

    @Test
    fun `picker exposes only server names and hides unresolved active ID`() {
        val restoredMarker = DjVoiceDescriptor(
            "private-local-id",
            "https://host/private/model.onnx?speaker=7",
            "v1",
            0,
            ""
        )

        val uiState = djVoiceSettingsUiState(
            DjVoiceState(
                catalog = DjVoiceCatalog(voices = listOf(baritone)),
                selectedId = baritone.id,
                activeVoice = restoredMarker
            )
        )

        assertEquals(listOf("Baritone"), uiState.options.map { it.displayName })
        assertEquals("Baritone", uiState.selectedVoiceName)
        assertEquals("Unavailable until catalog refresh", uiState.activeVoiceLabel)
        assertFalse(uiState.activeVoiceLabel.contains(restoredMarker.id))
        assertFalse(uiState.toString().contains("https://"))
        assertFalse(uiState.toString().contains("model.onnx"))
        assertFalse(uiState.toString().contains("speaker=7"))
    }

    @Test
    fun `active voice uses authenticated catalog display name`() {
        val restoredMarker = DjVoiceDescriptor(baritone.id, baritone.id, "v1", 0, "")

        val uiState = djVoiceSettingsUiState(
            DjVoiceState(
                catalog = DjVoiceCatalog(voices = listOf(baritone)),
                activeVoice = restoredMarker
            )
        )

        assertEquals("Baritone", uiState.activeVoiceLabel)
    }

    @Test
    fun `download and retry require a selected catalog descriptor`() {
        val catalog = DjVoiceCatalog(voices = listOf(baritone))

        assertFalse(
            djVoiceSettingsUiState(
                DjVoiceState(catalog = catalog),
                DjModelDownloadState.NotDownloaded
            ).canDownload
        )
        assertFalse(
            djVoiceSettingsUiState(
                DjVoiceState(catalog = catalog),
                DjModelDownloadState.Failed("failed")
            ).canDownload
        )
        assertTrue(
            djVoiceSettingsUiState(
                DjVoiceState(catalog = catalog, selectedId = baritone.id),
                DjModelDownloadState.NotDownloaded
            ).canDownload
        )
        assertTrue(
            djVoiceSettingsUiState(
                DjVoiceState(catalog = catalog, selectedId = baritone.id),
                DjModelDownloadState.Failed("failed")
            ).canDownload
        )
    }

    @Test
    fun `stale selected ID cannot download`() {
        val uiState = djVoiceSettingsUiState(
            DjVoiceState(
                catalog = DjVoiceCatalog(voices = listOf(baritone)),
                selectedId = "stale-id"
            )
        )

        assertFalse(uiState.canDownload)
    }
}
