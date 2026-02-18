package com.anyplayer.android.feature.state.transfer

import com.anyplayer.android.core.model.ColumnPreferences
import com.anyplayer.android.core.model.CustomPlaylist
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.PlaylistTrack
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.UnionPlaylistSource
import kotlinx.serialization.Serializable

const val ANY_PLAYER_STATE_FORMAT = "any-player-state"
const val ANY_PLAYER_STATE_VERSION = 1

@Serializable
data class AnyPlayerStateEnvelope(
    val format: String = ANY_PLAYER_STATE_FORMAT,
    val version: Int = ANY_PLAYER_STATE_VERSION,
    val createdAt: String,
    val sourceApp: SourceAppInfo,
    val data: AnyPlayerStateData,
    val integrity: Integrity
)

@Serializable
data class SourceAppInfo(
    val platform: String,
    val appVersion: String
)

@Serializable
data class AnyPlayerStateData(
    val customPlaylists: List<CustomPlaylist> = emptyList(),
    val playlistTracks: List<PlaylistTrack> = emptyList(),
    val unionPlaylistSources: List<UnionPlaylistSource> = emptyList(),
    val columnPreferences: Map<String, ColumnPreferences> = emptyMap(),
    val connections: Map<SourceType, ExportedConnection> = emptyMap(),
    val playbackState: PlaybackStatus? = null
)

@Serializable
data class ExportedConnection(
    val source: SourceType,
    val serverUrl: String? = null,
    val username: String? = null,
    val token: String? = null,
    val apiKey: String? = null,
    val spotifyConnected: Boolean? = null,
    val spotifyPremium: Boolean? = null
)

@Serializable
data class Integrity(
    val sha256: String
)

enum class ExportMode {
    PORTABLE,
    PRIVATE
}

enum class MergePolicy {
    REPLACE_ALL,
    MERGE_KEEP_LOCAL,
    MERGE_PREFER_IMPORT
}

data class ExportOptions(
    val mode: ExportMode,
    val includePlaybackState: Boolean,
    val passphrase: String? = null
)

data class ImportOptions(
    val mergePolicy: MergePolicy,
    val passphrase: String? = null,
    val dryRun: Boolean = false
)

data class ImportSummary(
    val playlistsAdded: Int,
    val playlistsUpdated: Int,
    val tracksAdded: Int,
    val tracksUpdated: Int,
    val unionLinksAdded: Int,
    val unionLinksUpdated: Int,
    val connectionsImported: Int,
    val connectionsSkipped: Int,
    val warnings: List<String>
)
