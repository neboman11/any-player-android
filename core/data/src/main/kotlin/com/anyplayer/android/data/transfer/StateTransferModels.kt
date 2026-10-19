package com.anyplayer.android.data.transfer

import com.anyplayer.android.core.model.*
import kotlinx.serialization.Serializable

const val ANY_PLAYER_STATE_FORMAT = "any-player-state"
const val ANY_PLAYER_STATE_VERSION = 1

// Declaration order and defaults are part of the legacy integrity canonicalization.
@Serializable
data class AnyPlayerStateEnvelope(
    val format: String = ANY_PLAYER_STATE_FORMAT,
    val version: Int = ANY_PLAYER_STATE_VERSION,
    val createdAt: String,
    val sourceApp: SourceAppInfo,
    val data: AnyPlayerStateData,
    val integrity: Integrity,
)
@Serializable
data class SourceAppInfo(val platform: String, val appVersion: String)
@Serializable
data class AnyPlayerStateData(
    val customPlaylists: List<CustomPlaylist> = emptyList(),
    val playlistTracks: List<PlaylistTrack> = emptyList(),
    val unionPlaylistSources: List<UnionPlaylistSource> = emptyList(),
    val columnPreferences: Map<String, ColumnPreferences> = emptyMap(),
    val connections: Map<SourceType, ExportedConnection> = emptyMap(),
    val playbackState: PlaybackStatus? = null,
)
@Serializable
data class ExportedConnection(
    val source: SourceType,
    val serverUrl: String? = null,
    val username: String? = null,
    val token: String? = null,
    val apiKey: String? = null,
    val spotifyConnected: Boolean? = null,
    val spotifyPremium: Boolean? = null,
)
@Serializable
data class Integrity(val sha256: String)
@Serializable
data class EncryptedStateFile(
    val format: String = ANY_PLAYER_STATE_FORMAT,
    val encrypted: Boolean = true,
    val algorithm: String = "AES-256-GCM",
    val kdf: String = "PBKDF2-HMAC-SHA256",
    val kdfIterations: Int,
    val saltBase64: String,
    val nonceBase64: String,
    val ciphertextBase64: String,
)
