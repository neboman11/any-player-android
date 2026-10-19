package com.anyplayer.android.data.sync

import kotlinx.coroutines.flow.StateFlow

data class SyncUiState(
    /** Legacy verbatim status strings ("Sync idle", "Sync pull complete.", …). */
    val status: String = "Sync idle",
    val inProgress: Boolean = false,
    /** Connect found data on the server: user must choose which side to keep. */
    val conflictPending: Boolean = false,
    val realtimeConnected: Boolean = false,
)

/**
 * Application-scoped sync with the Any Player sync server (legacy wire protocol). Settings live in
 * SyncSettingsStore; edits are persisted by the UI on commit and the realtime connection restarts
 * only on [connect].
 */
interface SyncEngine {
    val state: StateFlow<SyncUiState>

    /** Called once at startup (after restore, when the UI is STARTED). */
    fun start()
    fun connect()
    /** [confirmPlaylistOverwrite] required when local custom playlists exist and playlists sync is on. */
    fun pull(confirmPlaylistOverwrite: Boolean)
    fun resolveConflict(useLocal: Boolean)
    fun dismissConflict()
}

data class StartupStatus(
    val inProgress: Boolean = false,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
)

/** Process startup coordinator (design §5.8/§12.6). */
interface AppStartup {
    val status: StateFlow<StartupStatus>
    /** Idempotent; called from Application.onCreate. */
    fun start()
    fun retry()
}
