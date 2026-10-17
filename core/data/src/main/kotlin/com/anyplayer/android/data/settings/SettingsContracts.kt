package com.anyplayer.android.data.settings

import com.anyplayer.android.core.model.RepeatMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Legacy `sync_preferences_json` payload (EncryptedSharedPreferences `sync_preferences`).
 * Encoded with AppJson (defaults omitted) exactly like legacy.
 */
@Serializable
data class SyncPreferences(
    val serverTarget: String = "",
    val authToken: String = "",
    val syncAppState: Boolean = true,
    val syncPlaylists: Boolean = true,
    val syncProviderConfiguration: Boolean = true,
    val syncSettings: Boolean = true,
)

/** Sync server settings. Loaded once on IO; [preferences] is the in-memory source of truth. */
interface SyncSettingsStore {
    val preferences: StateFlow<SyncPreferences>
    /** Suspends until the first load from disk finished. */
    suspend fun awaitLoaded(): SyncPreferences
    suspend fun update(transform: (SyncPreferences) -> SyncPreferences)
    /** Legacy `sync_client_id`, created lazily. */
    suspend fun clientId(): String
}

data class DjSettings(
    val selectedVoiceId: String? = null,
    val selectedModelId: String? = null,
    val remoteGeneration: Boolean = true,
    val voiceLevel: Float = 1.0f,
)

/** Legacy DJ keys in `sync_preferences`: selected_dj_voice_id, selected_dj_model_id, dj_remote_generation, dj_voice_level. */
interface DjSettingsStore {
    val settings: StateFlow<DjSettings>
    suspend fun awaitLoaded(): DjSettings
    suspend fun update(transform: (DjSettings) -> DjSettings)
}

/** Non-secret playback settings (plain SharedPreferences `player_settings`). */
data class PlayerSettings(
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val volume: Int = 100,
    val positionMs: Long = 0,
    val normalizationEnabled: Boolean = false,
    val normalizationStrict: Boolean = false,
    val aiDjEnabled: Boolean = false,
    val showDjEntriesInQueue: Boolean = false,
)

interface PlayerSettingsStore {
    val settings: StateFlow<PlayerSettings>
    suspend fun awaitLoaded(): PlayerSettings
    /** Applied in memory immediately, persisted asynchronously on IO. */
    fun update(transform: (PlayerSettings) -> PlayerSettings)
}
