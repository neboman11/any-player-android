package com.anyplayer.android.dj

import com.anyplayer.android.data.settings.DjSettingsStore
import com.anyplayer.android.data.settings.PlayerSettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class PreparationStatus { NOT_STARTED, PROCESSING, READY }

/** UI-only facade. All actions are non-blocking; only download actions fetch artifact bytes. */
interface DjController {
    val enabled: StateFlow<Boolean>
    val showEntriesInQueue: StateFlow<Boolean>
    val remoteGeneration: StateFlow<Boolean>
    val songsAway: StateFlow<Int?>
    val preparationStatus: StateFlow<PreparationStatus>
    val modelCatalogState: StateFlow<ArtifactCatalogState<ModelDescriptor>>
    val modelDownloadState: StateFlow<ArtifactDownloadState>
    val downloadedModels: StateFlow<List<DownloadedArtifact>>
    val voiceCatalogState: StateFlow<ArtifactCatalogState<VoiceDescriptor>>
    val voiceDownloadState: StateFlow<ArtifactDownloadState>
    val downloadedVoices: StateFlow<List<DownloadedArtifact>>
    val voiceLevel: StateFlow<Float>
    val voiceLevelRange: ClosedFloatingPointRange<Float>
    val logs: StateFlow<List<DjLogEntry>>
    val debugLogging: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
    fun setShowEntriesInQueue(show: Boolean)
    fun setRemoteGeneration(remote: Boolean)
    fun refreshCatalogs()
    fun selectModel(id: String)
    fun downloadModel()
    fun deleteModel(item: DownloadedArtifact)
    fun selectVoice(id: String)
    fun downloadVoice()
    fun deleteVoice(item: DownloadedArtifact)
    fun setVoiceLevel(level: Float)
    fun clearLogs()
    fun setDebugLogging(enabled: Boolean)
}

@Singleton
class DefaultDjController internal constructor(
    private val player: PlayerSettingsStore,
    private val settings: DjSettingsStore,
    coordinator: DjCoordinator,
    private val models: ArtifactStore<ModelDescriptor>,
    private val voices: ArtifactStore<VoiceDescriptor>,
    private val local: OnDeviceBreakGenerator,
    private val diagnostics: DjDiagnostics,
    private val scope: CoroutineScope,
) : DjController {
    @Inject constructor(
        player: PlayerSettingsStore, settings: DjSettingsStore, coordinator: DjCoordinator,
        models: ArtifactStore<ModelDescriptor>, voices: ArtifactStore<VoiceDescriptor>,
        local: OnDeviceBreakGenerator, diagnostics: DjDiagnostics,
    ) : this(player, settings, coordinator, models, voices, local, diagnostics,
        CoroutineScope(SupervisorJob() + Dispatchers.IO))
    override val enabled = player.settings.map { it.aiDjEnabled }.stateIn(scope, SharingStarted.Eagerly, false)
    override val showEntriesInQueue = player.settings.map { it.showDjEntriesInQueue }.stateIn(scope, SharingStarted.Eagerly, false)
    override val remoteGeneration = settings.settings.map { it.remoteGeneration }.stateIn(scope, SharingStarted.Eagerly, true)
    override val voiceLevel = settings.settings.map { it.voiceLevel.coerceIn(0.5f, 2f) }.stateIn(scope, SharingStarted.Eagerly, 1f)
    override val voiceLevelRange = 0.5f..2f
    override val songsAway = coordinator.songsAway
    override val preparationStatus = coordinator.preparationStatus
    override val modelCatalogState = models.catalog
    override val modelDownloadState = models.download
    override val downloadedModels = models.downloaded
    override val voiceCatalogState = voices.catalog
    override val voiceDownloadState = voices.download
    override val downloadedVoices = voices.downloaded
    override val logs = diagnostics.entries
    override val debugLogging = diagnostics.debugLogging
    private var modelJob: Job? = null
    private var voiceJob: Job? = null
    init {
        action { models.initialize() }
        action { voices.initialize() }
        action {
            settings.awaitLoaded()
            settings.settings.collect { models.select(it.selectedModelId); voices.select(it.selectedVoiceId) }
        }
    }
    private fun action(block: suspend () -> Unit) { launchAction(block) }
    private fun launchAction(block: suspend () -> Unit): Job = scope.launch {
        try { block() } catch (e: Throwable) {
            if (e is CancellationException) throw e
            diagnostics.log("DjController", "DJ action failed", e)
        }
    }
    override fun setEnabled(enabled: Boolean) { player.update { it.copy(aiDjEnabled = enabled) } }
    override fun setShowEntriesInQueue(show: Boolean) { player.update { it.copy(showDjEntriesInQueue = show) } }
    override fun setRemoteGeneration(remote: Boolean) = action { settings.update { it.copy(remoteGeneration = remote) }; if (remote) local.release() }
    override fun refreshCatalogs() { action { models.refreshCatalog() }; action { voices.refreshCatalog() } }
    override fun selectModel(id: String) = action {
        if (models.catalog.value.items.any { it.id == id }) settings.update { it.copy(selectedModelId = id) }
    }
    override fun selectVoice(id: String) = action {
        if (voices.catalog.value.items.any { it.id == id }) settings.update { it.copy(selectedVoiceId = id) }
    }
    override fun downloadModel() {
        if (modelJob?.isActive == true) return
        modelJob = launchAction {
            if (models.catalog.value.items.isEmpty()) models.refreshCatalog()
            val selected = settings.awaitLoaded().selectedModelId ?: models.catalog.value.defaultId
            if (selected != null) { settings.update { it.copy(selectedModelId = selected) }; models.select(selected) }
            models.downloadSelected()
        }
    }
    override fun downloadVoice() {
        if (voiceJob?.isActive == true) return
        voiceJob = launchAction {
            if (voices.catalog.value.items.isEmpty()) voices.refreshCatalog()
            val selected = settings.awaitLoaded().selectedVoiceId ?: voices.catalog.value.defaultId
            if (selected != null) { settings.update { it.copy(selectedVoiceId = selected) }; voices.select(selected) }
            voices.downloadSelected()
        }
    }
    override fun deleteModel(item: DownloadedArtifact) = action { if (modelJob?.isActive != true) { local.release(); models.delete(item) } }
    override fun deleteVoice(item: DownloadedArtifact) = action { if (voiceJob?.isActive != true) { local.release(); voices.delete(item) } }
    override fun setVoiceLevel(level: Float) = action { if (level.isFinite()) settings.update { it.copy(voiceLevel = level.coerceIn(0.5f, 2f)) } }
    override fun clearLogs() = diagnostics.clear()
    override fun setDebugLogging(enabled: Boolean) = diagnostics.setDebugLogging(enabled)
}
