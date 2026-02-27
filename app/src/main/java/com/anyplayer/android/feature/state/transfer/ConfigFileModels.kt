package com.anyplayer.android.feature.state.transfer

import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.SourceType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val CONFIG_EXPORT_VERSION = 1

/**
 * Root model for the any-player config file format (export_version / provider_configs /
 * custom_playlists).  This is distinct from the full AnyPlayerStateEnvelope used by
 * device-to-device state transfer — it carries server URLs and playlist data but intentionally
 * omits auth tokens.
 */
@Serializable
data class ConfigFile(
    @SerialName("export_version") val exportVersion: Int,
    @SerialName("provider_configs") val providerConfigs: ConfigProviderConfigs,
    @SerialName("custom_playlists") val customPlaylists: List<ConfigCustomPlaylist> = emptyList()
)

@Serializable
data class ConfigProviderConfigs(
    val spotify: ConfigSpotify? = null,
    val jellyfin: ConfigJellyfin? = null,
    val plex: ConfigPlex? = null
)

@Serializable
data class ConfigSpotify(
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("redirect_uri") val redirectUri: String? = null
)

@Serializable
data class ConfigJellyfin(
    @SerialName("base_url") val baseUrl: String? = null
)

@Serializable
data class ConfigPlex(
    @SerialName("base_url") val baseUrl: String? = null
)

/** A playlist entry bundled with its tracks and union sources. */
@Serializable
data class ConfigCustomPlaylist(
    val playlist: ConfigPlaylistEntry,
    val tracks: List<ConfigTrack> = emptyList(),
    @SerialName("union_sources") val unionSources: List<ConfigUnionSource> = emptyList()
)

/** Core playlist metadata — dates are Unix epoch seconds. */
@Serializable
data class ConfigPlaylistEntry(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("track_count") val trackCount: Int,
    @SerialName("playlist_type") val playlistType: String
)

/** A track record inside a config playlist — `id` is a database row integer. */
@Serializable
data class ConfigTrack(
    val id: Int,
    @SerialName("playlist_id") val playlistId: String,
    @SerialName("track_source") val trackSource: String,
    @SerialName("track_id") val trackId: String,
    val position: Int,
    @SerialName("added_at") val addedAt: Long,
    val title: String,
    val artist: String,
    val album: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("image_url") val imageUrl: String? = null
)

/** A union-playlist source record — `id` is a database row integer. */
@Serializable
data class ConfigUnionSource(
    val id: Int,
    @SerialName("union_playlist_id") val unionPlaylistId: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("source_playlist_id") val sourcePlaylistId: String,
    val position: Int,
    @SerialName("added_at") val addedAt: Long
)

fun String.toConfigPlaylistTypeOrNull(): PlaylistType? = when (trim().lowercase()) {
    "standard" -> PlaylistType.STANDARD
    "union" -> PlaylistType.UNION
    else -> null
}

fun String.toConfigSourceTypeOrNull(): SourceType? = when (trim().lowercase()) {
    "spotify" -> SourceType.SPOTIFY
    "jellyfin" -> SourceType.JELLYFIN
    "plex" -> SourceType.PLEX
    "custom" -> SourceType.CUSTOM
    "all" -> SourceType.ALL
    else -> null
}
