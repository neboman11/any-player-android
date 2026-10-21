package com.anyplayer.android.ui.settings

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.*
import com.anyplayer.android.data.catalog.*
import com.anyplayer.android.data.settings.*
import com.anyplayer.android.data.sync.*
import com.anyplayer.android.data.transfer.*
import com.anyplayer.android.dj.*
import com.anyplayer.android.playback.*
import java.io.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonElement

internal class FakeSyncSettings : SyncSettingsStore {
    override val preferences = MutableStateFlow(SyncPreferences())
    val writes = Channel<SyncPreferences>(Channel.UNLIMITED)
    override suspend fun awaitLoaded() = preferences.value
    override suspend fun update(transform: (SyncPreferences) -> SyncPreferences) { preferences.value = transform(preferences.value); writes.send(preferences.value) }
    override suspend fun clientId() = "test"
}
internal class FakePlayerSettings : PlayerSettingsStore {
    override val settings = MutableStateFlow(PlayerSettings())
    override suspend fun awaitLoaded() = settings.value
    override fun update(transform: (PlayerSettings) -> PlayerSettings) { settings.value = transform(settings.value) }
}
internal class FakeSync : SyncEngine {
    override val state = MutableStateFlow(SyncUiState())
    val pulls = mutableListOf<Boolean>()
    val connects = Channel<Unit>(Channel.UNLIMITED)
    override fun start() = Unit
    override fun connect() { connects.trySend(Unit) }
    override fun pull(confirmPlaylistOverwrite: Boolean) { pulls += confirmPlaylistOverwrite }
    override fun resolveConflict(useLocal: Boolean) { state.value = state.value.copy(conflictPending = false) }
    override fun dismissConflict() { state.value = state.value.copy(conflictPending = false, status = "Sync connect cancelled.") }
}
internal class FakeEngine : PlaybackEngine {
    override val queue = MutableStateFlow(QueueState())
    override val player = MutableStateFlow(PlayerState())
    override val events = MutableSharedFlow<EngineEvent>()
    override val changes = MutableSharedFlow<EngineChange>()
    override fun positionMs() = 123L
    override suspend fun awaitRestored() = Unit
    override fun setQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean, origin: ChangeOrigin) = Unit
    override fun playNext(track: Track) = Unit
    override fun playEntry(uid: Long) = Unit
    override fun play(origin: ChangeOrigin) = Unit
    override fun pause(origin: ChangeOrigin) = Unit
    override fun togglePlayPause() = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun seekTo(ms: Long, origin: ChangeOrigin) = Unit
    override fun setVolume(percent: Int, origin: ChangeOrigin) { player.value = player.value.copy(volume = percent) }
    override fun setShuffle(on: Boolean, origin: ChangeOrigin) { queue.value = queue.value.copy(shuffle = on) }
    override fun setRepeat(mode: RepeatMode, origin: ChangeOrigin) { queue.value = queue.value.copy(repeat = mode) }
    override fun setNormalization(enabled: Boolean, strict: Boolean) { player.value = player.value.copy(normalization = enabled) }
    override fun setPendingBreak(pendingBreak: PendingBreak?) = Unit
}
internal class FakeTransfer : StateTransfer, ConfigFiles {
    var summary = ImportSummary(playlistsAdded = 2, tracksUpdated = 3, warnings = listOf("warning"))
    var exported: PlaybackStatus? = null
    var failure: String? = null
    override suspend fun export(output: OutputStream, mode: ExportMode, passphrase: String?, playback: PlaybackStatus?) { failure?.let { error(it) }; exported = playback }
    override suspend fun import(input: InputStream, policy: MergePolicy, passphrase: String?, dryRun: Boolean): ImportSummary { failure?.let { error(it) }; return summary }
    override suspend fun import(input: InputStream, policy: MergePolicy, dryRun: Boolean) = summary
    override suspend fun importJson(config: JsonElement, policy: MergePolicy, dryRun: Boolean) = summary
    override suspend fun exportPlaylistsJson(): JsonElement = error("Not exercised")
    override suspend fun exportProviderConfigJson(): JsonElement = error("Not exercised")
}
internal class FakeDocuments : SettingsDocuments {
    override fun displayName(uri: String) = "state.json"
    override suspend fun <T> read(uri: String, action: suspend (InputStream) -> T): T = ByteArrayInputStream(byteArrayOf()).use { action(it) }
    override suspend fun <T> write(uri: String, action: suspend (OutputStream) -> T): T = ByteArrayOutputStream().use { action(it) }
}
internal class FakeConnections : ConnectionStore {
    override val connections = MutableStateFlow<Map<SourceType, StoredConnection>>(emptyMap())
    override suspend fun awaitLoaded() = connections.value
    override suspend fun update(source: SourceType, transform: (StoredConnection?) -> StoredConnection?) {
        val updated = transform(connections.value[source])
        connections.value = connections.value.toMutableMap().apply {
            if (updated == null) remove(source) else put(source, updated)
        }
    }
}
internal class FakeAuth : AuthRepository {
    override val profiles = MutableStateFlow<Map<SourceType, ProviderConnectionProfile>>(emptyMap())
    var failure: String? = null
    var onCompleteSpotify: suspend () -> Unit = {}
    var pageSize = 0
    override suspend fun connectJellyfin(serverUrl: String, apiKey: String, pageSize: Int): ProviderConnectionProfile { failure?.let { error(it) }; return ProviderConnectionProfile(SourceType.JELLYFIN, true) }
    override suspend fun connectPlex(serverUrl: String, token: String, pageSize: Int): ProviderConnectionProfile { failure?.let { error(it) }; return ProviderConnectionProfile(SourceType.PLEX, true) }
    override suspend fun beginSpotifyAuth(): String { failure?.let { error(it) }; return "https://spotify.test" }
    override suspend fun completeSpotifyAuth(redirectUri: String): ProviderConnectionProfile {
        onCompleteSpotify()
        return ProviderConnectionProfile(SourceType.SPOTIFY, true)
    }
    override suspend fun disconnect(source: SourceType) = Unit
    override suspend fun updatePageSize(source: SourceType, pageSize: Int): Boolean { this.pageSize = pageSize; return true }
    override suspend fun restore() = emptyList<ProviderConnectionProfile>()
    override fun isSourceConnected(source: SourceType?) = true
}
internal class FakeCatalog : CatalogRepository {
    override val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    var failures = emptyList<ProviderRefreshFailure>()
    override suspend fun refreshPlaylists() = failures
    override suspend fun tracks(source: SourceType, playlistId: String, forceRefresh: Boolean) = emptyList<Track>()
    override suspend fun cachedTracks(source: SourceType, playlistId: String) = emptyList<Track>()
    override suspend fun refreshAll(onProgress: (String) -> Unit) = failures
    override suspend fun search(query: String, source: SourceType, type: SearchType): SearchResult = error("Not exercised")
    override suspend fun clearCache() = Unit
    override suspend fun isDistinct(source: SourceType, playlistId: String) = false
    override suspend fun setDistinct(source: SourceType, playlistId: String, distinct: Boolean) = Unit
}
internal class FakeDj : DjController {
    override val enabled = MutableStateFlow(false)
    override val showEntriesInQueue = MutableStateFlow(false)
    override val remoteGeneration = MutableStateFlow(true)
    override val songsAway = MutableStateFlow<Int?>(null)
    override val preparationStatus = MutableStateFlow(PreparationStatus.NOT_STARTED)
    override val modelCatalogState = MutableStateFlow(ArtifactCatalogState<ModelDescriptor>())
    override val modelDownloadState = MutableStateFlow<ArtifactDownloadState>(ArtifactDownloadState.NotDownloaded)
    override val downloadedModels = MutableStateFlow<List<DownloadedArtifact>>(emptyList())
    override val voiceCatalogState = MutableStateFlow(ArtifactCatalogState<VoiceDescriptor>())
    override val voiceDownloadState = MutableStateFlow<ArtifactDownloadState>(ArtifactDownloadState.NotDownloaded)
    override val downloadedVoices = MutableStateFlow<List<DownloadedArtifact>>(emptyList())
    override val voiceLevel = MutableStateFlow(1f)
    override val voiceLevelRange = 0.5f..2f
    override val logs = MutableStateFlow<List<DjLogEntry>>(emptyList())
    override val debugLogging = MutableStateFlow(false)
    val levelWrites = mutableListOf<Float>()
    var refreshes = 0
    override fun setEnabled(enabled: Boolean) { this.enabled.value = enabled }
    override fun setShowEntriesInQueue(show: Boolean) { showEntriesInQueue.value = show }
    override fun setRemoteGeneration(remote: Boolean) { remoteGeneration.value = remote }
    override fun refreshCatalogs() { refreshes++ }
    override fun selectModel(id: String) = Unit
    override fun downloadModel() = Unit
    override fun deleteModel(item: DownloadedArtifact) = Unit
    override fun selectVoice(id: String) = Unit
    override fun downloadVoice() = Unit
    override fun deleteVoice(item: DownloadedArtifact) = Unit
    override fun setVoiceLevel(level: Float) { levelWrites += level; voiceLevel.value = level }
    override fun clearLogs() { logs.value = emptyList() }
    override fun setDebugLogging(enabled: Boolean) { debugLogging.value = enabled }
}
