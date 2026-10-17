package com.anyplayer.android.sync

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.playlists.PlaylistRepository
import com.anyplayer.android.data.settings.PlayerSettingsStore
import com.anyplayer.android.data.settings.SyncPreferences
import com.anyplayer.android.data.settings.SyncSettingsStore
import com.anyplayer.android.data.sync.SyncEngine
import com.anyplayer.android.data.sync.SyncUiState
import com.anyplayer.android.data.transfer.ConfigFiles
import com.anyplayer.android.data.transfer.MergePolicy
import com.anyplayer.android.playback.ChangeOrigin
import com.anyplayer.android.playback.PlaybackEngine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Process-owned, event-driven sync. Only start/Connect confirms a realtime target. */
@Singleton
class DefaultSyncEngine internal constructor(
    private val engine: PlaybackEngine,
    private val settings: SyncSettingsStore,
    private val playerSettings: PlayerSettingsStore,
    private val playlists: PlaylistRepository,
    private val config: ConfigFiles,
    private val client: SyncClient,
    private val network: SyncNetwork,
    private val scope: CoroutineScope,
    private val applyDispatcher: CoroutineDispatcher,
    private val now: () -> Long,
    private val jitter: (Long) -> Long,
    private val imports: SyncImports,
) : SyncEngine {
    @Inject constructor(
        engine: PlaybackEngine,
        settings: SyncSettingsStore,
        playerSettings: PlayerSettingsStore,
        playlists: PlaylistRepository,
        config: ConfigFiles,
        client: SyncClient,
        network: SyncNetwork,
        imports: SyncImports,
    ) : this(engine, settings, playerSettings, playlists, config, client, network,
        CoroutineScope(SupervisorJob() + Dispatchers.IO), Dispatchers.Main.immediate,
        { System.nanoTime() / 1_000_000 }, { base -> Random.nextLong((base * 3 / 4).coerceAtLeast(1), base + 1) }, imports)

    private val mutableState = MutableStateFlow(SyncUiState())
    override val state: StateFlow<SyncUiState> = mutableState.asStateFlow()
    private val started = AtomicBoolean()
    private val observing = AtomicBoolean()
    private val operation = Mutex()
    private val revisions = AtomicLong()
    private val pushRequests = Channel<Unit>(Channel.CONFLATED)
    private var realtimeJob: Job? = null
    @Volatile private var observedQueue = false
    @Volatile private var suppressUntil = Long.MIN_VALUE
    private var lastAppPushAt = Long.MIN_VALUE
    @Volatile internal var lastSyncVersion = 0L
        private set
    private var versionTarget: Pair<String, String>? = null
    private var conflictPreferences: SyncPreferences? = null
    @Volatile private var confirmedPreferences: SyncPreferences? = null

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        observeEngine()
        scope.launch {
            engine.awaitRestored()
            settings.awaitLoaded()
            action(startup = true) {
                confirmedPreferences = it
                pullNow(it, confirm = false, startup = true)
            }
            beginRealtime()
        }
    }

    override fun connect() {
        // Explicit connection owns initial conflict resolution; a later startup must not pull over it.
        started.set(true)
        confirmedPreferences = null
        observeEngine()
        scope.launch {
            engine.awaitRestored()
            settings.awaitLoaded()
            // Do not race conflict detection with an existing socket or queued automatic pushes.
            realtimeJob?.cancelAndJoin()
            action { preferences ->
                confirmedPreferences = preferences
                val snapshot = client.snapshot(preferences)
                if (snapshot == null) status("Sync server unavailable.")
                else if (hasServerData(snapshot, preferences)) {
                    conflictPreferences = preferences
                    mutableState.update { it.copy(conflictPending = true, status = "Server already has synced data - choose which side to keep.") }
                } else {
                    conflictPreferences = null
                    mutableState.update { it.copy(conflictPending = false) }
                    status(if (pushLocal(preferences)) "Connected. Pushed local data to server." else "Connected, but some local data failed to push.")
                }
            }
            beginRealtime()
        }
    }

    override fun pull(confirmPlaylistOverwrite: Boolean) {
        observeEngine()
        scope.launch { engine.awaitRestored(); action { pullNow(it, confirmPlaylistOverwrite, startup = false) } }
    }

    override fun resolveConflict(useLocal: Boolean) {
        scope.launch {
            val selected = conflictPreferences ?: return@launch
            action { preferences ->
                if (target(selected) != target(preferences)) {
                    dismissConflict()
                    return@action
                }
                conflictPreferences = null
                mutableState.update { it.copy(conflictPending = false) }
                if (useLocal) status(if (pushLocal(preferences)) "Pushed local data to server." else "Some local data failed to push to server.")
                else pullNow(preferences, confirm = true, startup = false)
            }
        }
    }

    override fun dismissConflict() {
        conflictPreferences = null
        mutableState.update { it.copy(conflictPending = false, status = "Sync connect cancelled.") }
    }

    private suspend fun action(startup: Boolean = false, block: suspend (SyncPreferences) -> Unit) = operation.withLock {
        mutableState.update { it.copy(inProgress = true) }
        try {
            val preferences = settings.awaitLoaded()
            resetVersionIfNeeded(preferences)
            if (normalizeSyncBaseUrl(preferences.serverTarget).isEmpty()) status("Sync server target is not set.")
            else block(preferences)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            status(if (startup) "Sync server unavailable. Continued with local state." else "Sync server unavailable.")
        } finally { mutableState.update { it.copy(inProgress = false) } }
    }

    private fun target(preferences: SyncPreferences) = normalizeSyncBaseUrl(preferences.serverTarget) to normalizeSyncToken(preferences.authToken)
    private fun resetVersionIfNeeded(preferences: SyncPreferences) {
        if (versionTarget != target(preferences)) {
            versionTarget = target(preferences)
            lastSyncVersion = 0
        }
    }
    private fun status(value: String) { mutableState.update { it.copy(status = value) } }

    private suspend fun pullNow(preferences: SyncPreferences, confirm: Boolean, startup: Boolean) {
        val beforeQueue = engine.queue.value
        val beforeRevision = revisions.get()
        val snapshot = client.snapshot(preferences)
        if (snapshot == null) {
            status(if (startup) "Sync server unavailable. Continued with local state." else "Sync server unavailable.")
            return
        }
        val afterQueue = engine.queue.value
        val unchanged = beforeRevision == revisions.get() &&
            beforeQueue.contentVersion == afterQueue.contentVersion &&
            beforeQueue.current?.uid == afterQueue.current?.uid && beforeQueue.shuffle == afterQueue.shuffle
        val applied = applySnapshot(snapshot, preferences, confirm, preserveEmpty = startup, applyPlayback = !startup || unchanged)
        if (applied) status(if (!preferences.anyDomain()) "Sync completed with no selected domains." else "Sync pull complete.")
    }

    /** All playback commands execute together on Main, preserving the legacy ordering. */
    internal suspend fun applyAppState(data: JsonObject, preserveEmpty: Boolean = false) = withContext(Dispatchers.IO) {
        fun decodeTrack(element: JsonElement?): Track? = try {
            element?.let { AppJson.decodeFromJsonElement(Track.serializer(), it) }
        } catch (_: Exception) { null }
        val current = decodeTrack(data["current_track"])
        val remoteQueue = (data["queue"] as? JsonArray)?.mapNotNull(::decodeTrack).orEmpty()
        if (preserveEmpty && current == null && remoteQueue.isEmpty()) return@withContext
        suppressUntil = now() + ECHO_WINDOW_MS
        val tracks = if (current != null && remoteQueue.none { sameTrack(it, current) }) listOf(current) + remoteQueue else remoteQueue
        withContext(applyDispatcher) {
            data.number("volume")?.let { engine.setVolume(it.toInt(), ChangeOrigin.SYNC) }
            data.boolean("shuffle")?.takeIf { it != engine.queue.value.shuffle }?.let { engine.setShuffle(it, ChangeOrigin.SYNC) }
            engine.setRepeat(when (data.string("repeat_mode")) { "one" -> RepeatMode.ONE; "all" -> RepeatMode.ALL; else -> RepeatMode.OFF }, ChangeOrigin.SYNC)
            val local = engine.queue.value
            if (current != null) {
                if (tracks != local.entries.map { it.track } || local.current?.track != current) {
                    engine.setQueue(tracks, tracks.indexOfFirst { sameTrack(it, current) }.coerceAtLeast(0), autoPlay = false, origin = ChangeOrigin.SYNC)
                }
            } else if (remoteQueue.isEmpty() && local.entries.isNotEmpty()) {
                engine.setQueue(emptyList(), autoPlay = false, origin = ChangeOrigin.SYNC)
            }
            data.number("position")?.let { if (abs(engine.positionMs() - it) > 2_500) engine.seekTo(it, ChangeOrigin.SYNC) }
            when (data.string("state")) {
                "playing" -> engine.play(ChangeOrigin.SYNC)
                "paused", "stopped" -> engine.pause(ChangeOrigin.SYNC)
            }
            suppressUntil = now() + ECHO_WINDOW_MS
        }
    }

    private suspend fun applySnapshot(
        snapshot: JsonObject,
        preferences: SyncPreferences,
        confirm: Boolean,
        preserveEmpty: Boolean = false,
        applyPlayback: Boolean = true,
        appStateOnly: Boolean = false,
        realtime: Boolean = false,
    ): Boolean {
        if (!appStateOnly && preferences.syncSettings) {
            val data = snapshot["settings"] as? JsonObject ?: JsonObject(emptyMap())
            val local = playerSettings.awaitLoaded()
            val enabled = data.boolean("audio_normalization_enabled") ?: data.boolean("audioNormalizationEnabled") ?: local.normalizationEnabled
            val strict = data.boolean("audio_normalization_strict_mode") ?: data.boolean("audioNormalizationStrictMode") ?: local.normalizationStrict
            playerSettings.update { it.copy(normalizationEnabled = enabled, normalizationStrict = strict) }
            withContext(applyDispatcher) {
                suppressUntil = now() + ECHO_WINDOW_MS
                engine.setNormalization(enabled, strict)
            }
        }
        if (preferences.syncAppState && applyPlayback) {
            (snapshot["app_state"] as? JsonObject)?.let { applyAppState(it, preserveEmpty) }
        }
        var applied = true
        if (!appStateOnly && (preferences.syncPlaylists || preferences.syncProviderConfiguration)) {
            if (preferences.syncPlaylists && !confirm &&
                (withTimeoutOrNull(5_000) { playlists.customPlaylists.first().isNotEmpty() } ?: true)) {
                if (!realtime) status("Sync cancelled: playlist overwrite requires confirmation.")
                applied = realtime
            } else {
                val file = buildJsonObject {
                    put("export_version", 1)
                    put("provider_configs", if (preferences.syncProviderConfiguration) snapshot["provider_configuration"] ?: JsonObject(emptyMap()) else JsonObject(emptyMap()))
                    put("custom_playlists", if (preferences.syncPlaylists) snapshot["playlists"] ?: JsonArray(emptyList()) else JsonArray(emptyList()))
                }
                applied = imports.apply(file, if (preferences.syncPlaylists) MergePolicy.REPLACE_ALL else MergePolicy.MERGE_KEEP_LOCAL)
            }
        }
        // Versions belong to the entire snapshot, not just the app-state domain.
        lastSyncVersion = maxOf(lastSyncVersion, snapshot.number("version") ?: lastSyncVersion)
        return applied
    }

    private fun observeEngine() {
        if (!observing.compareAndSet(false, true)) return
        scope.launch {
            engine.awaitRestored()
            if (engine.queue.value.entries.isNotEmpty()) observedQueue = true
            launch { engine.queue.collect { if (it.entries.isNotEmpty()) observedQueue = true } }
            launch {
                // EngineChange covers discrete queue, status, volume and exact-position seek mutations.
                engine.changes.collect { change ->
                    if (change.origin == ChangeOrigin.USER) {
                        revisions.incrementAndGet()
                        if (engine.queue.value.entries.isNotEmpty()) observedQueue = true
                        if (canPush()) pushRequests.trySend(Unit)
                    }
                }
            }
            for (ignored in pushRequests) {
                if (lastAppPushAt != Long.MIN_VALUE) delay((MIN_PUSH_INTERVAL_MS - (now() - lastAppPushAt)).coerceAtLeast(0))
                // Consume changes coalesced during the throttle delay; serialize the latest state once.
                while (pushRequests.tryReceive().isSuccess) Unit
                operation.withLock {
                    val preferences = realtimePreferences() ?: return@withLock
                    if (canPush() && preferences.syncAppState && normalizeSyncBaseUrl(preferences.serverTarget).isNotEmpty()) {
                        pushAppState(preferences)
                    }
                }
            }
        }
    }

    private fun canPush() = confirmedPreferences != null && observedQueue && now() >= suppressUntil && !state.value.conflictPending

    private fun realtimePreferences(): SyncPreferences? = confirmedPreferences?.let { confirmed ->
        settings.preferences.value.copy(serverTarget = confirmed.serverTarget, authToken = confirmed.authToken)
    }

    private suspend fun pushAppState(preferences: SyncPreferences): Boolean {
        if (!canPush()) return true
        if (lastAppPushAt != Long.MIN_VALUE) delay((MIN_PUSH_INTERVAL_MS - (now() - lastAppPushAt)).coerceAtLeast(0))
        if (!canPush()) return true
        val data = withContext(applyDispatcher) { AppStateSyncPayload.capture(engine) }.toJson()
        val success = client.push(preferences, "app-state", settings.clientId(), data)
        // Even failed attempts are throttled: never burst on a failing server.
        lastAppPushAt = now()
        return success
    }

    private suspend fun pushLocal(preferences: SyncPreferences): Boolean = supervisorScope {
        val id = settings.clientId()
        val pushes = buildList {
            if (preferences.syncAppState) add(async { pushAppState(preferences) })
            if (preferences.syncSettings) add(async {
                val values = playerSettings.awaitLoaded()
                client.push(preferences, "settings", id, buildJsonObject {
                    put("audio_normalization_enabled", values.normalizationEnabled)
                    put("audio_normalization_strict_mode", values.normalizationStrict)
                })
            })
            if (preferences.syncPlaylists) add(async { domainPush { client.push(preferences, "playlists", id, config.exportPlaylistsJson()) } })
            if (preferences.syncProviderConfiguration) add(async { domainPush { client.push(preferences, "provider-configuration", id, config.exportProviderConfigJson()) } })
        }
        pushes.awaitAll().all { it }
    }

    private suspend fun domainPush(block: suspend () -> Boolean): Boolean = try { block() } catch (e: Exception) {
        if (e is CancellationException) throw e
        false
    }

    private fun beginRealtime() {
        realtimeJob?.cancel()
        val confirmed = confirmedPreferences ?: return
        realtimeJob = scope.launch {
            network.available.collectLatest { online ->
                if (!online) { mutableState.update { it.copy(realtimeConnected = false) }; return@collectLatest }
                reconnect(confirmed)
            }
        }
    }

    private suspend fun reconnect(preferences: SyncPreferences) {
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            try {
                client.updates(preferences).collect { message ->
                    when (message) {
                        SocketMessage.Open -> {
                            backoff = 1_000L
                            mutableState.update { it.copy(realtimeConnected = true) }
                        }
                        is SocketMessage.Update -> operation.withLock { receiveUpdate(preferences, message.event) }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            } finally { mutableState.update { it.copy(realtimeConnected = false) } }
            delay(jitter(backoff))
            backoff = (backoff * 2).coerceAtMost(60_000)
        }
    }

    internal suspend fun receiveUpdate(preferences: SyncPreferences, event: SyncUpdate) {
        if (state.value.conflictPending || event.sourceClientId == settings.clientId()) return
        val current = realtimePreferences() ?: preferences
        if (target(preferences) != target(current)) return
        resetVersionIfNeeded(preferences)
        if (event.version != null && event.version <= lastSyncVersion) return
        when (event.namespace) {
            "app_state" -> if (current.syncAppState) {
                val snapshot = client.appState(preferences) ?: client.snapshot(preferences, lastSyncVersion) ?: return
                applySnapshot(snapshot, current, confirm = false, appStateOnly = true)
            }
            "playlists", "provider_configuration", "settings" -> {
                val selected = when (event.namespace) { "playlists" -> current.syncPlaylists; "settings" -> current.syncSettings; else -> current.syncProviderConfiguration }
                if (selected) client.snapshot(preferences, lastSyncVersion)?.let {
                    val domain = current.copy(
                        syncAppState = false, syncPlaylists = event.namespace == "playlists",
                        syncSettings = event.namespace == "settings",
                        syncProviderConfiguration = event.namespace == "provider_configuration",
                    )
                    applySnapshot(it, domain, confirm = false, applyPlayback = false, realtime = true)
                }
            }
        }
    }

    internal companion object {
        const val ECHO_WINDOW_MS = 3_500L
        const val MIN_PUSH_INTERVAL_MS = 2_000L
        fun hasServerData(snapshot: JsonObject, preferences: SyncPreferences): Boolean {
            val app = snapshot["app_state"] as? JsonObject
            val hasTrack = try { app?.get("current_track")?.let { AppJson.decodeFromJsonElement(Track.serializer(), it) } != null } catch (_: Exception) { false }
            return (preferences.syncAppState && (hasTrack || (app?.get("queue") as? JsonArray)?.isNotEmpty() == true)) ||
                (preferences.syncPlaylists && (snapshot["playlists"] as? JsonArray)?.isNotEmpty() == true) ||
                (preferences.syncProviderConfiguration && (snapshot["provider_configuration"] as? JsonObject)?.isNotEmpty() == true) ||
                (preferences.syncSettings && (snapshot["settings"] as? JsonObject)?.isNotEmpty() == true)
        }
        private fun sameTrack(a: Track, b: Track) = a.id == b.id && a.source == b.source
    }
}

private fun SyncPreferences.anyDomain() = syncAppState || syncPlaylists || syncSettings || syncProviderConfiguration

/** Legacy payload: queue is insertion order, not shuffled play order. Defaults omit null/empty data. */
internal data class AppStateSyncPayload(
    val state: String,
    val shuffle: Boolean,
    val repeatMode: String,
    val volume: Int,
    val position: Long,
    val duration: Long,
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("state", state); put("shuffle", shuffle); put("repeat_mode", repeatMode)
        put("volume", volume); put("position", position); put("duration", duration)
        currentTrack?.let { put("current_track", AppJson.encodeToJsonElement(Track.serializer(), it)) }
        if (queue.isNotEmpty()) put("queue", JsonArray(queue.map { AppJson.encodeToJsonElement(Track.serializer(), it) }))
    }
    companion object {
        fun capture(engine: PlaybackEngine): AppStateSyncPayload {
            val queue = engine.queue.value
            val player = engine.player.value
            return AppStateSyncPayload(
                when (player.status) { PlaybackStateType.PLAYING -> "playing"; PlaybackStateType.PAUSED -> "paused"; else -> "stopped" },
                queue.shuffle, queue.repeat.name.lowercase(), player.volume, engine.positionMs(), player.durationMs,
                queue.current?.track, queue.entries.map { it.track },
            )
        }
    }
}
