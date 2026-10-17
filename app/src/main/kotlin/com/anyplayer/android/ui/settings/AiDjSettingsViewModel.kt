package com.anyplayer.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.dj.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.log10

data class AiDjSettingsState(
    val enabled: Boolean = false,
    val showEntries: Boolean = false,
    val remote: Boolean = true,
    val models: ArtifactCatalogState<ModelDescriptor> = ArtifactCatalogState(),
    val modelDownload: ArtifactDownloadState = ArtifactDownloadState.NotDownloaded,
    val downloadedModels: List<DownloadedArtifact> = emptyList(),
    val voices: ArtifactCatalogState<VoiceDescriptor> = ArtifactCatalogState(),
    val voiceDownload: ArtifactDownloadState = ArtifactDownloadState.NotDownloaded,
    val downloadedVoices: List<DownloadedArtifact> = emptyList(),
    val voiceLevel: Float = 1f,
    val logs: List<DjLogEntry> = emptyList(),
    val debug: Boolean = false,
)

@HiltViewModel
class AiDjSettingsViewModel @Inject constructor(private val dj: DjController) : ViewModel() {
    private val mutableState = MutableStateFlow(AiDjSettingsState())
    val state = mutableState.asStateFlow()
    val voiceLevelRange = dj.voiceLevelRange
    private var dragging = false
    private var entered = false
    private var refreshed = false
    init {
        viewModelScope.launch {
            dj.enabled.collect { value ->
                if (entered && value && !refreshed) { refreshed = true; dj.refreshCatalogs() }
                mutableState.update { it.copy(enabled = value) }
            }
        }
        observe(dj.showEntriesInQueue) { old, value -> old.copy(showEntries = value) }
        observe(dj.remoteGeneration) { old, value -> old.copy(remote = value) }
        observe(dj.modelCatalogState) { old, value -> old.copy(models = value) }
        observe(dj.modelDownloadState) { old, value -> old.copy(modelDownload = value) }
        observe(dj.downloadedModels) { old, value -> old.copy(downloadedModels = value) }
        observe(dj.voiceCatalogState) { old, value -> old.copy(voices = value) }
        observe(dj.voiceDownloadState) { old, value -> old.copy(voiceDownload = value) }
        observe(dj.downloadedVoices) { old, value -> old.copy(downloadedVoices = value) }
        observe(dj.voiceLevel) { old, value -> if (dragging) old else old.copy(voiceLevel = value) }
        observe(dj.logs) { old, value -> old.copy(logs = value.asReversed()) }
        observe(dj.debugLogging) { old, value -> old.copy(debug = value) }
    }
    private fun <T> observe(flow: StateFlow<T>, reduce: (AiDjSettingsState, T) -> AiDjSettingsState) {
        viewModelScope.launch { flow.collect { value -> mutableState.update { reduce(it, value) } } }
    }
    fun enter() {
        if (entered) return
        entered = true
        refreshed = false
        if (dj.enabled.value) { refreshed = true; dj.refreshCatalogs() }
    }
    fun leave() { entered = false }
    fun setEnabled(value: Boolean) = dj.setEnabled(value)
    fun setShowEntries(value: Boolean) = dj.setShowEntriesInQueue(value)
    fun setRemote(value: Boolean) = dj.setRemoteGeneration(value)
    fun refreshCatalogs() = dj.refreshCatalogs()
    fun selectModel(id: String) = dj.selectModel(id)
    fun selectVoice(id: String) = dj.selectVoice(id)
    fun downloadModel() = dj.downloadModel()
    fun downloadVoice() = dj.downloadVoice()
    fun deleteModel(item: DownloadedArtifact) = dj.deleteModel(item)
    fun deleteVoice(item: DownloadedArtifact) = dj.deleteVoice(item)
    fun editVoiceLevel(value: Float) {
        if (!value.isFinite()) return
        dragging = true
        mutableState.update { it.copy(voiceLevel = value.coerceIn(voiceLevelRange)) }
    }
    fun commitVoiceLevel() {
        if (!dragging) return
        dj.setVoiceLevel(state.value.voiceLevel)
        dragging = false
    }
    fun clearLogs() = dj.clearLogs()
    fun setDebug(value: Boolean) = dj.setDebugLogging(value)
    fun copyLogs(): String = state.value.logs.joinToString("\n") { "${it.timestamp} ${it.tag}: ${it.message}" }
}

internal fun voiceLevelLabel(gain: Float): String = String.format(Locale.getDefault(), "Voice level: %+.1f dB", 20 * log10(gain.toDouble()))

internal fun <T : ArtifactDescriptor> activeArtifactLabel(catalog: ArtifactCatalogState<T>, download: ArtifactDownloadState): String {
    val id = catalog.activeId ?: (download as? ArtifactDownloadState.Ready)?.file?.name
    return catalog.items.firstOrNull { it.id == id }?.name ?: if (id == null) "None" else "Unavailable until catalog refresh"
}
internal fun <T : ArtifactDescriptor> canDownload(catalog: ArtifactCatalogState<T>, download: ArtifactDownloadState): Boolean {
    val selected = catalog.items.firstOrNull { it.id == catalog.selectedId } ?: return false
    val active = catalog.activeId ?: (download as? ArtifactDownloadState.Ready)?.file?.name
    return download !is ArtifactDownloadState.Downloading &&
        (download is ArtifactDownloadState.NotDownloaded || download is ArtifactDownloadState.Failed || selected.id != active)
}
