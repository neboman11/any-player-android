package com.anyplayer.android.app

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import com.anyplayer.android.feature.state.transfer.ConfigFileImporter
import com.anyplayer.android.feature.state.transfer.ImportSummary
import com.anyplayer.android.feature.state.transfer.MergePolicy
import com.anyplayer.android.feature.sync.SyncPreferences
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import com.anyplayer.android.feature.sync.SyncSnapshotClient
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val TAG = "SyncStateHolder"

/**
 * Owns MainViewModel's sync-server preferences, manual pull, and the realtime
 * app-state push/pull loop. [pullSyncState] also applies the playlists/provider-config
 * domains via [ConfigFileImporter], reporting the outcome through [applyImportSummary]
 * (a callback into MainViewModel's [StateTransferStateHolder]) since that status flow
 * is shared with the state-transfer feature.
 */
internal class SyncStateHolder(
    private val viewModelScope: CoroutineScope,
    private val syncPreferencesStore: SyncPreferencesStore,
    private val syncSnapshotClient: SyncSnapshotClient,
    private val playbackQueueManager: PlaybackQueueManager,
    private val configFileImporter: ConfigFileImporter,
    private val customPlaylistCount: () -> Int,
    private val applyImportSummary: (prefix: String, summary: ImportSummary) -> Unit,
    private val onSyncApplied: suspend () -> Unit
) {
    private val syncJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }

    val syncServerTarget = MutableStateFlow("")
    val syncAuthToken = MutableStateFlow("")
    val syncAppStateEnabled = MutableStateFlow(true)
    val syncPlaylistsEnabled = MutableStateFlow(true)
    val syncProviderConfigurationEnabled = MutableStateFlow(true)
    val syncSettingsEnabled = MutableStateFlow(true)
    val syncStatus = MutableStateFlow("Sync idle")

    private var lastSyncVersion: Long = 0
    private var suppressSyncPushUntilMs: Long = 0
    private var lastPushedSignature: String = ""
    private var lastPushAtMs: Long = 0
    /** The startup pullSyncState() REST fetch and the realtime WS sync loop both
     *  independently fetch and apply the server's app_state snapshot - without this,
     *  their network round-trips can resolve within milliseconds of each other, both
     *  read the same stale local playback state, and both call setQueue() with the
     *  same queue, corrupting the ExoPlayer/decoder pipeline mid-init. */
    private val applyAppStateMutex = Mutex()

    init {
        loadSyncPreferences()
    }

    fun updateSyncServerTarget(value: String) {
        syncServerTarget.value = value
        persistSyncPreferences()
    }

    fun updateSyncAuthToken(value: String) {
        syncAuthToken.value = value
        persistSyncPreferences()
    }

    fun updateSyncAppStateEnabled(value: Boolean) {
        syncAppStateEnabled.value = value
        persistSyncPreferences()
    }

    fun updateSyncPlaylistsEnabled(value: Boolean) {
        syncPlaylistsEnabled.value = value
        persistSyncPreferences()
    }

    fun updateSyncProviderConfigurationEnabled(value: Boolean) {
        syncProviderConfigurationEnabled.value = value
        persistSyncPreferences()
    }

    fun updateSyncSettingsEnabled(value: Boolean) {
        syncSettingsEnabled.value = value
        persistSyncPreferences()
    }

    fun pullSyncState(confirmPlaylistOverwrite: Boolean) {
        viewModelScope.launch {
            val preferences = currentSyncPreferences()
            if (preferences.serverTarget.isBlank()) {
                syncStatus.value = "Sync server target is not set."
                return@launch
            }

            val snapshot = syncSnapshotClient.fetchSnapshot(preferences.serverTarget)
            if (snapshot == null) {
                syncStatus.value = "Sync server unavailable. Continued with local state."
                return@launch
            }

            var applied = 0

            if (preferences.syncSettings) {
                applySettingsDomain(snapshot)
                applied += 1
            }

            if (preferences.syncAppState) {
                applyAppStateMutex.withLock { applyAppStateDomain(snapshot) }
                applied += 1
            }

            if (preferences.syncPlaylists || preferences.syncProviderConfiguration) {
                val imported = applyConfigDomains(
                    snapshot = snapshot,
                    includePlaylists = preferences.syncPlaylists,
                    includeProviderConfiguration = preferences.syncProviderConfiguration,
                    confirmPlaylistOverwrite = confirmPlaylistOverwrite
                )
                if (imported) {
                    applied += 1
                }
            }

            if (applied == 0) {
                syncStatus.value = "Sync completed with no selected domains."
            } else {
                syncStatus.value = "Sync pull complete."
            }

            onSyncApplied()
        }
    }

    fun startRealtimePlaybackSync() {
        viewModelScope.launch {
            combine(syncServerTarget, syncAppStateEnabled) { serverTarget, appStateEnabled ->
                Pair(serverTarget.trim(), appStateEnabled)
            }.collectLatest { (serverTarget, appStateEnabled) ->
                if (!appStateEnabled || serverTarget.isBlank()) {
                    return@collectLatest
                }

                val clientId = syncSnapshotClient.getClientId()

                coroutineScope {
                    val pushJob = launch {
                        playbackQueueManager.status.collect { status ->
                            if (System.currentTimeMillis() < suppressSyncPushUntilMs) {
                                return@collect
                            }

                            val payload = syncSnapshotClient.payloadFromPlayback(status)
                            val positionBucket = payload.position / 5000L
                            val signature = buildString {
                                append(payload.state)
                                append('|')
                                append(payload.current_track?.source?.name ?: "none")
                                append(':')
                                append(payload.current_track?.id ?: "none")
                                append('|')
                                append(payload.shuffle)
                                append('|')
                                append(payload.repeat_mode)
                                append('|')
                                append(payload.volume)
                                append('|')
                                append(positionBucket)
                            }

                            val now = System.currentTimeMillis()
                            if (signature == lastPushedSignature && now - lastPushAtMs < 15_000L) {
                                return@collect
                            }

                            val pushResult = runCatching {
                                syncSnapshotClient.pushAppState(serverTarget, payload)
                            }
                            if (pushResult.isFailure) {
                                CompatLog.w(TAG, "sync push failed", pushResult.exceptionOrNull())
                            }

                            if (pushResult.getOrDefault(false)) {
                                lastPushedSignature = signature
                                lastPushAtMs = now
                            }
                        }
                    }

                    val wsJob = launch {
                        while (true) {
                            val result = runCatching {
                                syncSnapshotClient.observeStateUpdates(serverTarget).collect { event ->
                                    if (event.event_type != "state_updated") {
                                        return@collect
                                    }
                                    if (event.namespace != "app_state") {
                                        return@collect
                                    }
                                    if (!event.source_client_id.isNullOrBlank() && event.source_client_id == clientId) {
                                        return@collect
                                    }
                                    if (event.version != null && event.version <= lastSyncVersion) {
                                        return@collect
                                    }

                                    val snapshot = syncSnapshotClient.fetchSnapshotSince(serverTarget, max(0L, lastSyncVersion))
                                        ?: return@collect

                                    suppressSyncPushUntilMs = System.currentTimeMillis() + 3500L
                                    applyAppStateMutex.withLock { applyAppStateDomain(snapshot) }
                                    val nextVersion = snapshot["version"]?.jsonPrimitive?.longOrNull ?: lastSyncVersion
                                    lastSyncVersion = max(lastSyncVersion, nextVersion)
                                }
                            }

                            if (result.isSuccess) {
                                delay(1500L)
                            } else {
                                CompatLog.w(TAG, "sync ws collect error", result.exceptionOrNull())
                                delay(2500L)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadSyncPreferences() {
        val value = syncPreferencesStore.read()
        syncServerTarget.value = value.serverTarget
        syncAuthToken.value = value.authToken
        syncAppStateEnabled.value = value.syncAppState
        syncPlaylistsEnabled.value = value.syncPlaylists
        syncProviderConfigurationEnabled.value = value.syncProviderConfiguration
        syncSettingsEnabled.value = value.syncSettings
    }

    private fun persistSyncPreferences() {
        syncPreferencesStore.save(currentSyncPreferences())
    }

    private fun currentSyncPreferences(): SyncPreferences = SyncPreferences(
        serverTarget = syncServerTarget.value.trim(),
        authToken = syncAuthToken.value.trim(),
        syncAppState = syncAppStateEnabled.value,
        syncPlaylists = syncPlaylistsEnabled.value,
        syncProviderConfiguration = syncProviderConfigurationEnabled.value,
        syncSettings = syncSettingsEnabled.value
    )

    private suspend fun applyConfigDomains(
        snapshot: JsonObject,
        includePlaylists: Boolean,
        includeProviderConfiguration: Boolean,
        confirmPlaylistOverwrite: Boolean
    ): Boolean {
        val remotePlaylists = (snapshot["playlists"] as? JsonArray)
        val remotePlaylistCount = remotePlaylists?.size ?: 0
        val localPlaylistCount = customPlaylistCount()

        if (includePlaylists && localPlaylistCount > 0 && !confirmPlaylistOverwrite) {
            syncStatus.value = "Sync cancelled: playlist overwrite requires confirmation."
            return false
        }

        val providerConfiguration = if (includeProviderConfiguration) {
            snapshot["provider_configuration"]
        } else {
            null
        }

        val configPayload = JsonObject(
            mapOf(
                "export_version" to JsonPrimitive(1),
                "provider_configs" to (providerConfiguration ?: JsonObject(emptyMap())),
                "custom_playlists" to if (includePlaylists) {
                    snapshot["playlists"] ?: JsonArray(emptyList())
                } else {
                    JsonArray(emptyList())
                }
            )
        )

        val summary = withContext(Dispatchers.IO) {
            configFileImporter.importFromStream(
                stream = ByteArrayInputStream(configPayload.toString().toByteArray()),
                mergePolicy = if (includePlaylists) MergePolicy.REPLACE_ALL else MergePolicy.MERGE_KEEP_LOCAL,
                dryRun = false
            )
        }

        applyImportSummary("Sync import", summary)
        return includePlaylists || includeProviderConfiguration
    }

    private fun applySettingsDomain(snapshot: JsonObject) {
        val settings = snapshot["settings"]?.asObjectOrNull() ?: return
        val enabled = settings.boolean("audio_normalization_enabled")
            ?: settings.boolean("audioNormalizationEnabled")
        val strictMode = settings.boolean("audio_normalization_strict_mode")
            ?: settings.boolean("audioNormalizationStrictMode")

        if (enabled != null || strictMode != null) {
            val currentSettings = playbackQueueManager.audioNormalizationSettings.value
            playbackQueueManager.setAudioNormalization(
                enabled = enabled ?: currentSettings.enabled,
                strictMode = strictMode ?: currentSettings.strictMode
            )
        }
    }

    private fun applyAppStateDomain(snapshot: JsonObject) {
        val appState = snapshot["app_state"]?.asObjectOrNull() ?: return
        val localState = playbackQueueManager.status.value

        appState.int("volume")?.let { volume ->
            playbackQueueManager.setVolume(volume)
        }

        appState.boolean("shuffle")?.let { shuffle ->
            playbackQueueManager.setShuffle(shuffle)
        }

        appState.string("repeat_mode")?.let { repeatMode ->
            val mode = when (repeatMode.lowercase()) {
                "one" -> RepeatMode.ONE
                "all" -> RepeatMode.ALL
                else -> RepeatMode.OFF
            }
            playbackQueueManager.setRepeatMode(mode)
        }

        val remoteCurrentTrack = appState.track("current_track")
        val remoteQueue = appState.trackList("queue")
        if (remoteCurrentTrack != null) {
            val sameCurrentTrack = localState.currentTrack?.id == remoteCurrentTrack.id &&
                localState.currentTrack.source == remoteCurrentTrack.source
            if (!sameCurrentTrack) {
                playbackQueueManager.setQueue(listOf(remoteCurrentTrack) + remoteQueue, startIndex = 0, autoPlay = false)
            }
        }

        appState.long("position")?.let { positionMs ->
            if (abs(localState.position - positionMs) > 2500L) {
                playbackQueueManager.seekTo(positionMs)
            }
        }

        appState.string("state")?.lowercase()?.let { state ->
            when (state) {
                "playing" -> playbackQueueManager.play()
                "paused" -> playbackQueueManager.pause()
                "stopped" -> playbackQueueManager.pause()
            }
        }
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.doubleOrNull?.toInt()
    }

    private fun JsonObject.long(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
    }

    private fun JsonObject.track(key: String): Track? {
        val element = this[key] ?: return null
        return runCatching {
            syncJson.decodeFromJsonElement(Track.serializer(), element)
        }.getOrNull()
    }

    private fun JsonObject.trackList(key: String): List<Track> {
        val raw = this[key] as? JsonArray ?: return emptyList()
        return raw.mapNotNull { element ->
            runCatching {
                syncJson.decodeFromJsonElement(Track.serializer(), element)
            }.getOrNull()
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
