package com.anyplayer.android.sync

import androidx.room.withTransaction
import com.anyplayer.android.data.db.AppDatabase
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.net.normalizeSyncServerAuthToken
import com.anyplayer.android.data.net.normalizeSyncServerBaseUrl
import com.anyplayer.android.data.playlists.PlaylistRepository
import com.anyplayer.android.data.settings.PlayerSettingsStore
import com.anyplayer.android.data.settings.SyncPreferences
import com.anyplayer.android.data.settings.SyncSettingsStore
import com.anyplayer.android.data.sync.SyncEngine
import com.anyplayer.android.data.sync.SyncUiState
import com.anyplayer.android.data.transfer.ConfigFiles
import com.anyplayer.android.data.transfer.MergePolicy
import com.anyplayer.android.data.transfer.ImportSummary
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
    private val base: SyncBaseStore,
    private val playlistTransaction: suspend (suspend () -> Unit) -> Unit,
    private val playlistChanges: Flow<*>,
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
        base: SyncBaseStore,
        database: AppDatabase,
    ) : this(engine, settings, playerSettings, playlists, config, client, network,
        CoroutineScope(SupervisorJob() + Dispatchers.IO), Dispatchers.Main.immediate,
        { System.nanoTime() / 1_000_000 }, { jitterBase -> Random.nextLong((jitterBase * 3 / 4).coerceAtLeast(1), jitterBase + 1) },
        imports, base, { block -> database.withTransaction { block() } },
        database.invalidationTracker.createFlow("custom_playlists", "playlist_tracks", "union_playlist_sources"))

    private val mutableState = MutableStateFlow(SyncUiState())
    override val state: StateFlow<SyncUiState> = mutableState.asStateFlow()
    private val started = AtomicBoolean()
    private val observing = AtomicBoolean()
    private val operation = Mutex()
    private val revisions = AtomicLong()
    private val pushRequests = Channel<Unit>(Channel.CONFLATED)
    // Guards realtimeJob against a concurrent Cancel (dismissConflict runs on the caller's thread).
    private val realtimeLock = Any()
    @Volatile private var realtimeJob: Job? = null
    @Volatile private var observedQueue = false
    @Volatile private var suppressUntil = Long.MIN_VALUE
    private var lastAppPushAt = Long.MIN_VALUE
    @Volatile internal var lastSyncVersion = 0L
        private set
    private var versionTarget: Pair<String, String>? = null
    private var conflictPreferences: SyncPreferences? = null
    @Volatile private var confirmedPreferences: SyncPreferences? = null

    private val playlistPush = DomainPush(SyncBase.PLAYLISTS, "playlists", { it.syncPlaylists }, requireBase = true,
        { config.exportPlaylistsJson() }, ::playlistsSignature)
    private val settingsPush = DomainPush(SyncBase.SETTINGS, "settings", { it.syncSettings }, requireBase = false,
        { playerSettings.awaitLoaded().let { settingsJson(it.normalizationEnabled, it.normalizationStrict) } }, ::settingsSignature)

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        observeLocalChanges()
        scope.launch {
            engine.awaitRestored()
            settings.awaitLoaded()
            action(startup = true) {
                pullNow(it, confirm = false, startup = true)
            }
            beginRealtime()
        }
    }

    override fun connect() {
        // Explicit connection owns initial conflict resolution; a later startup must not pull over it.
        started.set(true)
        confirmedPreferences = null
        observeLocalChanges()
        scope.launch {
            engine.awaitRestored()
            settings.awaitLoaded()
            // Do not race conflict detection with an existing socket or queued automatic pushes.
            realtimeJob?.cancelAndJoin()
            action { preferences ->
                val snapshot = client.snapshot(preferences)
                if (snapshot == null) {
                    confirmedPreferences = null
                    status("Sync server unavailable.")
                } else {
                    confirmedPreferences = preferences
                    if (hasServerData(snapshot, preferences)) {
                        conflictPreferences = preferences
                        mutableState.update { it.copy(conflictPending = true, status = "Server already has synced data - choose which side to keep.") }
                    } else {
                        conflictPreferences = null
                        mutableState.update { it.copy(conflictPending = false) }
                        status(if (pushLocal(preferences)) "Connected. Pushed local data to server." else "Connected, but some local data failed to push.")
                    }
                }
            }
            beginRealtime()
        }
    }

    override fun pull(confirmPlaylistOverwrite: Boolean) {
        observeLocalChanges()
        mutableState.update { it.copy(playlistConfirmationRequired = false) }
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

    /** Cancel undoes Connect: no automatic push or remote apply until the next Connect or app start. */
    override fun dismissConflict() {
        synchronized(realtimeLock) {
            confirmedPreferences = null
            realtimeJob?.cancel()
        }
        conflictPreferences = null
        mutableState.update { it.copy(conflictPending = false, status = "Sync connect cancelled.") }
    }

    private suspend fun action(startup: Boolean = false, block: suspend (SyncPreferences) -> Unit) = operation.withLock {
        mutableState.update { it.copy(inProgress = true) }
        try {
            val preferences = settings.awaitLoaded()
            resetVersionIfNeeded(preferences)
            if (normalizeSyncServerBaseUrl(preferences.serverTarget).isEmpty()) status("Sync server target is not set.")
            else block(preferences)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            status(if (startup) "Sync server unavailable. Continued with local state." else "Sync server unavailable.")
        } finally { mutableState.update { it.copy(inProgress = false) } }
    }

    private fun target(preferences: SyncPreferences) = normalizeSyncServerBaseUrl(preferences.serverTarget) to normalizeSyncServerAuthToken(preferences.authToken)
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
        if (startup) confirmedPreferences = preferences
        val afterQueue = engine.queue.value
        val unchanged = beforeRevision == revisions.get() &&
            beforeQueue.contentVersion == afterQueue.contentVersion &&
            beforeQueue.current?.uid == afterQueue.current?.uid && beforeQueue.shuffle == afterQueue.shuffle
        val applied = applySnapshot(snapshot, preferences, confirm, preserveEmpty = startup, applyPlayback = !startup || unchanged,
            merge = startup, startup = startup)
        if (applied) status(if (!preferences.anyDomain()) "Sync completed with no selected domains." else "Sync pull complete.")
    }

    /**
     * Startup and realtime pulls merge against the last state this device exchanged with the server: local
     * playback changed while the server was unreachable, or before sync started (e.g. under Android Auto),
     * is pushed instead of being rewound to a server copy no other client changed. Live local playback is
     * never replaced by the startup pull. Explicit pulls and conflict resolution always apply the server.
     */
    private suspend fun reconcileAppState(data: JsonObject, preferences: SyncPreferences, preserveEmpty: Boolean, merge: Boolean, startup: Boolean) {
        val target = syncBaseTarget(preferences)
        if (merge) {
            val agreed = base.get(SyncBase.APP_STATE, target)
            val local = localSignature()
            val localNewer = agreed != null && remoteSignature(data) == agreed.remote && local != agreed.local
            if ((startup && engine.player.value.playRequested) || localNewer) {
                if (local != agreed?.local) pushAppState(preferences)
                return
            }
        }
        if (applyAppState(data, preserveEmpty)) base.set(SyncBase.APP_STATE, target, remoteSignature(data), localSignature())
    }

    private fun localSignature(): String {
        val q = engine.queue.value
        return appStateSignature(q.current?.track, q.entries.map { it.track }, q.shuffle, q.repeat.name.lowercase())
    }

    private fun remoteSignature(data: JsonObject) = appStateSignature(decodeTrack(data["current_track"]),
        (data["queue"] as? JsonArray)?.mapNotNull(::decodeTrack).orEmpty(), data.boolean("shuffle"), remoteRepeat(data).name.lowercase())

    /** All playback commands execute together on Main, preserving the legacy ordering. False when nothing was applied. */
    internal suspend fun applyAppState(data: JsonObject, preserveEmpty: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val current = decodeTrack(data["current_track"])
        val remoteQueue = (data["queue"] as? JsonArray)?.mapNotNull(::decodeTrack).orEmpty()
        if (preserveEmpty && current == null && remoteQueue.isEmpty()) return@withContext false
        suppressUntil = now() + ECHO_WINDOW_MS
        val tracks = if (current != null && remoteQueue.none { sameTrack(it, current) }) listOf(current) + remoteQueue else remoteQueue
        withContext(applyDispatcher) {
            data.number("volume")?.let { engine.setVolume(it.toInt(), ChangeOrigin.SYNC) }
            data.boolean("shuffle")?.takeIf { it != engine.queue.value.shuffle }?.let { engine.setShuffle(it, ChangeOrigin.SYNC) }
            engine.setRepeat(remoteRepeat(data), ChangeOrigin.SYNC)
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
        true
    }

    private suspend fun applySnapshot(
        snapshot: JsonObject,
        preferences: SyncPreferences,
        confirm: Boolean,
        preserveEmpty: Boolean = false,
        applyPlayback: Boolean = true,
        appStateOnly: Boolean = false,
        realtime: Boolean = false,
        merge: Boolean = false,
        startup: Boolean = false,
    ): Boolean {
        val target = syncBaseTarget(preferences)
        snapshot.number("version")?.let { version ->
            if (base.version(target)?.let { version < it } == true) {
                base.clear(target)
                lastSyncVersion = 0
            }
        }
        if (!appStateOnly && preferences.syncSettings) {
            val data = snapshot["settings"] as? JsonObject ?: JsonObject(emptyMap())
            val local = playerSettings.awaitLoaded()
            val enabled = data.boolean("audio_normalization_enabled") ?: data.boolean("audioNormalizationEnabled") ?: local.normalizationEnabled
            val strict = data.boolean("audio_normalization_strict_mode") ?: data.boolean("audioNormalizationStrictMode") ?: local.normalizationStrict
            val agreed = base.get(SyncBase.SETTINGS, target)
            val localData = settingsJson(local.normalizationEnabled, local.normalizationStrict)
            if (startup && agreed != null && settingsSignature(data) == agreed.remote &&
                settingsSignature(localData) != agreed.local) {
                settingsPush.push(preferences, localData)
            } else {
                playerSettings.update { it.copy(normalizationEnabled = enabled, normalizationStrict = strict) }
                withContext(applyDispatcher) {
                    suppressUntil = now() + ECHO_WINDOW_MS
                    engine.setNormalization(enabled, strict)
                }
                settingsPush.record(target, data, settingsJson(enabled, strict))
            }
        }
        if (preferences.syncAppState && applyPlayback) {
            (snapshot["app_state"] as? JsonObject)?.let { reconcileAppState(it, preferences, preserveEmpty, merge, startup) }
        }
        var applied = true
        if (!appStateOnly && (preferences.syncPlaylists || preferences.syncProviderConfiguration)) {
            var decision = PlaylistMerge.UNCHANGED
            applied = imports.apply {
                var summary: ImportSummary? = null
                // Merge reads, REPLACE_ALL and the resulting local base share the importer's Room transaction.
                // A concurrent writer therefore lands before the comparison or after the recorded import.
                playlistTransaction {
                    decision = if (preferences.syncPlaylists && !confirm) mergePlaylists(snapshot, target, realtime) else PlaylistMerge.APPLY_REMOTE
                    if (decision == PlaylistMerge.CONFIRM) {
                        playlistPush.awaitingConfirmation = true
                        mutableState.update { it.copy(playlistConfirmationRequired = true) }
                        if (!realtime) status("Sync cancelled: playlist overwrite requires confirmation.")
                    } else {
                        val replace = preferences.syncPlaylists && decision == PlaylistMerge.APPLY_REMOTE
                        if (replace || preferences.syncProviderConfiguration) {
                            val file = buildJsonObject {
                                put("export_version", 1)
                                put("provider_configs", if (preferences.syncProviderConfiguration) snapshot["provider_configuration"] ?: JsonObject(emptyMap()) else JsonObject(emptyMap()))
                                put("custom_playlists", if (replace) snapshot["playlists"] ?: JsonArray(emptyList()) else JsonArray(emptyList()))
                            }
                            summary = config.importJson(file, if (replace) MergePolicy.REPLACE_ALL else MergePolicy.MERGE_KEEP_LOCAL, dryRun = false)
                            if (replace) playlistPush.record(target, snapshot["playlists"] ?: JsonArray(emptyList()), config.exportPlaylistsJson())
                        }
                    }
                }
                summary
            }
            if (decision == PlaylistMerge.CONFIRM && !realtime) applied = false
            if (decision == PlaylistMerge.PUSH_LOCAL) playlistPush.push(preferences)
        }
        // Versions belong to the entire snapshot, not just the app-state domain.
        lastSyncVersion = maxOf(lastSyncVersion, snapshot.number("version") ?: lastSyncVersion)
        snapshot.number("version")?.let { base.recordVersion(target, it) }
        return applied
    }

    private enum class PlaylistMerge { APPLY_REMOTE, PUSH_LOCAL, UNCHANGED, CONFIRM }

    /**
     * Three-way merge against the playlists base. When only one side changed since the base, that side
     * wins silently. When both changed, realtime keeps this device's copy (it made the latest edit) while
     * startup and manual pulls ask, as does any pull before the first sync with this target (no base) with
     * local playlists, or while an earlier pull still awaits that confirmation.
     */
    private suspend fun mergePlaylists(snapshot: JsonObject, target: String, realtime: Boolean): PlaylistMerge {
        val local = config.exportPlaylistsJson()
        val localPlaylists = (local as? JsonArray)?.isNotEmpty() == true
        if (localPlaylists && (snapshot["playlists"] as? JsonArray).isNullOrEmpty()) return PlaylistMerge.CONFIRM
        val agreed = base.get(SyncBase.PLAYLISTS, target)
        if (agreed == null) {
            val remote = snapshot["playlists"] ?: JsonArray(emptyList())
            if (playlistsSignature(local) == playlistsSignature(remote)) {
                playlistPush.record(target, remote, local)
                return PlaylistMerge.UNCHANGED
            }
            return if (localPlaylists) PlaylistMerge.CONFIRM else PlaylistMerge.APPLY_REMOTE
        }
        if (playlistPush.awaitingConfirmation) return PlaylistMerge.CONFIRM
        val localChanged = playlistsSignature(local) != agreed.local
        val remoteChanged = playlistsSignature(snapshot["playlists"]) != agreed.remote
        return when {
            !remoteChanged -> if (localChanged) PlaylistMerge.PUSH_LOCAL else PlaylistMerge.UNCHANGED
            !localChanged -> PlaylistMerge.APPLY_REMOTE
            realtime -> PlaylistMerge.PUSH_LOCAL
            else -> PlaylistMerge.CONFIRM
        }
    }

    private fun observeLocalChanges() {
        if (!observing.compareAndSet(false, true)) return
        scope.launch {
            launch { playlistPush.consume() }
            launch { settingsPush.consume() }
            // Watch child tables directly: a track/source write need not also touch its parent.
            launch { playlistChanges.collect { playlistPush.request() } }
            launch { playerSettings.settings.map { settingsJson(it.normalizationEnabled, it.normalizationStrict) }.distinctUntilChanged().collect { settingsPush.request() } }
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
                    if (canPush() && preferences.syncAppState && normalizeSyncServerBaseUrl(preferences.serverTarget).isNotEmpty()) {
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
        val payload = withContext(applyDispatcher) { AppStateSyncPayload.capture(engine) }
        val success = client.push(preferences, "app-state", settings.clientId(), payload.toJson())
        // Even failed attempts are throttled: never burst on a failing server.
        lastAppPushAt = now()
        if (success) base.set(SyncBase.APP_STATE, syncBaseTarget(preferences), payload.signature())
        return success
    }

    private suspend fun pushLocal(preferences: SyncPreferences): Boolean = supervisorScope {
        val id = settings.clientId()
        val pushes = buildList {
            if (preferences.syncAppState) add(async { pushAppState(preferences) })
            if (preferences.syncSettings) add(async { domainPush { settingsPush.push(preferences) } })
            if (preferences.syncPlaylists) add(async { domainPush { playlistPush.push(preferences) } })
            if (preferences.syncProviderConfiguration) add(async { domainPush { client.push(preferences, "provider-configuration", id, config.exportProviderConfigJson()) } })
        }
        pushes.awaitAll().all { it }
    }

    private suspend fun domainPush(block: suspend () -> Boolean): Boolean = try { block() } catch (e: Exception) {
        if (e is CancellationException) throw e
        false
    }

    private fun canPushDomains() = confirmedPreferences != null && !state.value.conflictPending

    /**
     * Playlists or settings. While sync is confirmed, local changes are pushed at least
     * [MIN_PUSH_INTERVAL_MS] apart (failed attempts included), and only when the local export differs
     * from the merge base, so a remote copy just applied is never echoed back.
     */
    private inner class DomainPush(
        val domain: SyncBase,
        val namespace: String,
        val selected: (SyncPreferences) -> Boolean,
        /** Without a base only Connect or a confirmed pull may decide which side wins. */
        val requireBase: Boolean,
        val export: suspend () -> JsonElement,
        val signature: (JsonElement) -> String,
    ) {
        val requests = Channel<Unit>(Channel.CONFLATED)
        /** A pull is waiting for the user to confirm a playlist overwrite; pushing would bypass that choice. */
        @Volatile var awaitingConfirmation = false
        private var lastPushAt = Long.MIN_VALUE
        private var retryJob: Job? = null
        private var retryDelay = MIN_PUSH_INTERVAL_MS
        private var pendingTarget: String? = null

        fun request() { if (canPushDomains()) requests.trySend(Unit) }

        suspend fun consume() {
            for (ignored in requests) {
                throttle()
                // Consume changes coalesced during the throttle delay; export the latest state once.
                while (requests.tryReceive().isSuccess) Unit
                operation.withLock {
                    val preferences = realtimePreferences() ?: return@withLock
                    val target = syncBaseTarget(preferences)
                    if (!canPushDomains() || !selected(preferences) || target.isEmpty() || awaitingConfirmation) return@withLock
                    val agreed = base.get(domain, target)
                    if (agreed == null && requireBase && pendingTarget != target) return@withLock
                    domainPush {
                        val data = export()
                        signature(data) == agreed?.local || push(preferences, data)
                    }
                }
            }
        }

        suspend fun push(preferences: SyncPreferences, data: JsonElement? = null): Boolean {
            throttle()
            val payload = data ?: export()
            pendingTarget = syncBaseTarget(preferences)
            val success = domainPush { client.push(preferences, namespace, settings.clientId(), payload) }
            // Even failed attempts are throttled: never burst on a failing server.
            lastPushAt = now()
            if (success) {
                record(syncBaseTarget(preferences), payload)
                retryJob?.cancel()
                retryJob = null
                retryDelay = MIN_PUSH_INTERVAL_MS
                pendingTarget = null
            } else if (retryJob == null) {
                val backoff = retryDelay
                retryDelay = (retryDelay * 2).coerceAtMost(60_000)
                retryJob = scope.launch {
                    delay(backoff)
                    retryJob = null
                    request()
                }
            }
            return success
        }

        /** Remote wire data and its locally applied representation may differ after a lossy import. */
        suspend fun record(target: String, data: JsonElement, local: JsonElement = data) {
            base.set(domain, target, signature(data), signature(local))
            awaitingConfirmation = false
            if (domain == SyncBase.PLAYLISTS) mutableState.update { it.copy(playlistConfirmationRequired = false) }
        }

        private suspend fun throttle() {
            if (lastPushAt != Long.MIN_VALUE) delay((MIN_PUSH_INTERVAL_MS - (now() - lastPushAt)).coerceAtLeast(0))
        }
    }

    private fun beginRealtime() {
        synchronized(realtimeLock) {
            realtimeJob?.cancel()
            val confirmed = confirmedPreferences ?: return
            realtimeJob = scope.launch {
                network.available.collectLatest { online ->
                    if (!online) { mutableState.update { it.copy(realtimeConnected = false) }; return@collectLatest }
                    reconnect(confirmed)
                }
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
                            playlistPush.request()
                            settingsPush.request()
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
                applySnapshot(snapshot, current, confirm = false, appStateOnly = true, merge = true)
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
    }
}

private fun SyncPreferences.anyDomain() = syncAppState || syncPlaylists || syncSettings || syncProviderConfiguration

private fun decodeTrack(element: JsonElement?): Track? = try {
    element?.let { AppJson.decodeFromJsonElement(Track.serializer(), it) }
} catch (_: Exception) { null }

private fun remoteRepeat(data: JsonObject) = when (data.string("repeat_mode")) { "one" -> RepeatMode.ONE; "all" -> RepeatMode.ALL; else -> RepeatMode.OFF }

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
    fun signature() = appStateSignature(currentTrack, queue, shuffle, repeatMode)
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
