package com.anyplayer.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.data.settings.PlayerSettingsStore
import com.anyplayer.android.data.settings.SyncPreferences
import com.anyplayer.android.data.settings.SyncSettingsStore
import com.anyplayer.android.data.sync.SyncEngine
import com.anyplayer.android.data.sync.SyncUiState
import com.anyplayer.android.data.transfer.*
import com.anyplayer.android.playback.PlaybackEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GeneralSettingsState(
    val serverTarget: String = "",
    val authToken: String = "",
    val preferences: SyncPreferences = SyncPreferences(),
    val sync: SyncUiState = SyncUiState(),
    val normalization: Boolean = false,
    val confirmOverwrite: Boolean = false,
    val transferStatus: String = "State transfer idle",
    val transferBusy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class GeneralSettingsViewModel @Inject constructor(
    private val syncSettings: SyncSettingsStore,
    private val playerSettings: PlayerSettingsStore,
    private val syncEngine: SyncEngine,
    private val engine: PlaybackEngine,
    private val transfer: StateTransfer,
    private val config: ConfigFiles,
    private val documents: SettingsDocuments,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GeneralSettingsState())
    val state = mutableState.asStateFlow()
    private var targetDirty = false
    private var tokenDirty = false
    private val commits = Mutex()

    init {
        viewModelScope.launch {
            syncSettings.awaitLoaded()
            syncSettings.preferences.collect { prefs ->
                mutableState.update { it.copy(preferences = prefs,
                    serverTarget = if (targetDirty) it.serverTarget else prefs.serverTarget,
                    authToken = if (tokenDirty) it.authToken else prefs.authToken) }
            }
        }
        viewModelScope.launch { syncEngine.state.collect { sync -> mutableState.update { it.copy(sync = sync) } } }
        viewModelScope.launch { playerSettings.settings.collect { settings -> mutableState.update { it.copy(normalization = settings.normalizationEnabled) } } }
    }

    fun editTarget(value: String) { targetDirty = true; mutableState.update { it.copy(serverTarget = value) } }
    fun editToken(value: String) { tokenDirty = true; mutableState.update { it.copy(authToken = value) } }
    fun commitFields() { viewModelScope.launch { persistFields() } }

    private suspend fun persistFields(): Boolean = commits.withLock {
        val target = state.value.serverTarget
        val token = state.value.authToken
        val saveTarget = targetDirty
        val saveToken = tokenDirty
        try {
            withContext(Dispatchers.IO) {
                syncSettings.awaitLoaded()
                if (saveTarget || saveToken) syncSettings.update {
                    it.copy(serverTarget = if (saveTarget) target else it.serverTarget,
                        authToken = if (saveToken) token else it.authToken)
                }
            }
            if (state.value.serverTarget == target) targetDirty = false
            if (state.value.authToken == token) tokenDirty = false
            mutableState.update { it.copy(error = null) }
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableState.update { it.copy(error = "Settings save failed: ${e.message ?: "Unknown error"}") }
            false
        }
    }

    fun setDomain(domain: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { syncSettings.update { prefs -> when (domain) {
                    "app_state" -> prefs.copy(syncAppState = enabled)
                    "playlists" -> prefs.copy(syncPlaylists = enabled)
                    "provider_configuration" -> prefs.copy(syncProviderConfiguration = enabled)
                    "settings" -> prefs.copy(syncSettings = enabled)
                    else -> prefs
                } } }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(error = "Settings save failed: ${e.message ?: "Unknown error"}") }
            }
        }
    }
    fun connect() { viewModelScope.launch { if (persistFields()) syncEngine.connect() } }
    fun requestPull() {
        if (state.value.preferences.syncPlaylists) mutableState.update { it.copy(confirmOverwrite = true) }
        else pullAfterCommit(false)
    }
    fun confirmPull() { mutableState.update { it.copy(confirmOverwrite = false) }; pullAfterCommit(true) }
    fun cancelPull() { mutableState.update { it.copy(confirmOverwrite = false) } }
    private fun pullAfterCommit(overwrite: Boolean) {
        if (!targetDirty && !tokenDirty) syncEngine.pull(overwrite)
        else viewModelScope.launch { if (persistFields()) syncEngine.pull(overwrite) }
    }
    fun resolveConflict(useLocal: Boolean) = syncEngine.resolveConflict(useLocal)
    fun cancelConflict() = syncEngine.dismissConflict()
    fun setNormalization(enabled: Boolean) {
        engine.setNormalization(enabled)
        playerSettings.update { it.copy(normalizationEnabled = enabled) }
    }

    suspend fun documentName(uri: String): String = withContext(Dispatchers.IO) { documents.displayName(uri) }

    fun export(uri: String, mode: ExportMode, passphrase: String?) {
        // Snapshot queue/player together on Main; the potentially large serialization runs on IO.
        val queue = engine.queue.value
        val player = engine.player.value
        val position = engine.positionMs()
        runTransfer("Export") {
            val playback = PlaybackStatus(player.status, queue.shuffle, queue.repeat, player.volume,
                queue.current?.track, position, player.durationMs,
                queue.entries.map { it.track }, queue.order.map { queue.entries[it].track }, player.error)
            documents.write(uri) { transfer.export(it, mode, passphrase?.takeIf(String::isNotBlank), playback) }
            "Export complete"
        }
    }
    fun import(uri: String, policy: MergePolicy, passphrase: String?, dryRun: Boolean, configFile: Boolean) {
        val prefix = if (configFile) { if (dryRun) "Config dry run" else "Config import" } else { if (dryRun) "Dry run" else "Import" }
        runTransfer(if (configFile) "Config import" else prefix) {
            documents.read(uri) {
                if (configFile) config.import(it, policy, dryRun)
                else transfer.import(it, policy, passphrase?.takeIf(String::isNotBlank), dryRun)
            }.format(prefix)
        }
    }
    private fun runTransfer(prefix: String, action: suspend () -> String) {
        if (state.value.transferBusy) return
        mutableState.update { it.copy(transferBusy = true) }
        viewModelScope.launch {
            try {
                val message = withContext(Dispatchers.IO) { action() }
                mutableState.update { it.copy(transferStatus = message) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(transferStatus = "$prefix failed: ${e.message ?: "Unknown error"}") }
            } finally { mutableState.update { it.copy(transferBusy = false) } }
        }
    }
}
