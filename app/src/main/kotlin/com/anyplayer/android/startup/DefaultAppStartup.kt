package com.anyplayer.android.startup

import com.anyplayer.android.data.auth.AuthRepository
import com.anyplayer.android.data.catalog.CatalogRepository
import com.anyplayer.android.data.sync.AppStartup
import com.anyplayer.android.data.sync.StartupStatus
import com.anyplayer.android.data.sync.SyncEngine
import com.anyplayer.android.dj.DjController
import com.anyplayer.android.playback.PlaybackEngine
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Optional legacy provider-cache migration, bound by integration; implementations must be idempotent. */
fun interface LegacyCacheImport {
    suspend fun runOnce()
}

/** Network startup work waits for visible UI or actual playback; cold media browsing stays local. */
interface StartupGate {
    suspend fun awaitReady()
}

@Singleton
class DefaultAppStartup internal constructor(
    private val engine: Provider<PlaybackEngine>,
    private val auth: Provider<AuthRepository>,
    private val catalog: Provider<CatalogRepository>,
    private val sync: Provider<SyncEngine>,
    private val dj: Provider<DjController>,
    private val cache: Optional<LegacyCacheImport>,
    private val gate: StartupGate,
    private val scope: CoroutineScope,
) : AppStartup {
    @Inject constructor(
        engine: Provider<PlaybackEngine>, auth: Provider<AuthRepository>, catalog: Provider<CatalogRepository>,
        sync: Provider<SyncEngine>, dj: Provider<DjController>, cache: Optional<LegacyCacheImport>, gate: StartupGate,
    ) : this(engine, auth, catalog, sync, dj, cache, gate, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val mutableStatus = MutableStateFlow(StartupStatus())
    override val status: StateFlow<StartupStatus> = mutableStatus.asStateFlow()
    private val started = AtomicBoolean()
    private val running = AtomicBoolean()

    override fun start() {
        if (started.compareAndSet(false, true)) runStartup()
    }

    override fun retry() {
        started.set(true)
        runStartup()
    }

    private fun runStartup() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            val warnings = mutableListOf<String>()
            fun publish(message: String, inProgress: Boolean = true) {
                mutableStatus.value = StartupStatus(inProgress, message, warnings.toList())
            }
            suspend fun attempt(prefix: String, block: suspend () -> Unit) {
                try { block() } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    warnings += "$prefix: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            try {
                publish("Restoring playback...")
                attempt("Playback restore failed") { engine.get().awaitRestored() }
                publish("Restoring provider sessions...")
                var restoredCount = 0
                // AuthRepository owns the per-provider parallelism (Jellyfin/Plex are local-only).
                attempt("Session restore failed") { restoredCount = auth.get().restore().size }
                publish("Waiting for playback or app...")
                gate.awaitReady()
                // Coordinator construction can immediately generate enabled DJ breaks.
                attempt("DJ startup failed") { dj.get() }
                publish("Loading cached playlists...")
                attempt("Legacy cache import failed") { if (cache.isPresent) cache.get().runOnce() }
                publish("Loading provider playlists...")
                attempt("Playlist refresh failed") {
                    warnings += catalog.get().refreshPlaylists().map { "Playlist refresh failed: ${it.source}: ${it.message}" }
                }
                // Sync.start owns its startup pull followed by realtime. Never repeat auth/catalog.
                attempt("Sync startup failed") { sync.get().start() }
                publish(if (restoredCount == 0) "Startup complete. No providers restored; running with local/cached data."
                    else "Startup complete. $restoredCount provider session(s) restored.", inProgress = false)
            } finally {
                mutableStatus.update { it.copy(inProgress = false, warnings = warnings.toList()) }
                running.set(false)
            }
        }
    }
}
