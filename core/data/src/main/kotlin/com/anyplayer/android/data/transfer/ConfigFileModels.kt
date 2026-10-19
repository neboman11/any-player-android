package com.anyplayer.android.data.transfer

import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.SourceType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

const val CONFIG_EXPORT_VERSION = 1
@Serializable
data class ConfigFile(
    @SerialName("export_version") val exportVersion: Int,
    @SerialName("provider_configs") val providerConfigs: ConfigProviderConfigs,
    @SerialName("custom_playlists") val customPlaylists: List<ConfigCustomPlaylist> = emptyList(),
)
@Serializable
data class ConfigProviderConfigs(val spotify: ConfigSpotify? = null, val jellyfin: ConfigJellyfin? = null, val plex: ConfigPlex? = null)
@Serializable
data class ConfigSpotify(@SerialName("client_id") val clientId: String? = null, @SerialName("redirect_uri") val redirectUri: String? = null)
@Serializable
data class ConfigJellyfin(@SerialName("base_url") val baseUrl: String? = null)
@Serializable
data class ConfigPlex(@SerialName("base_url") val baseUrl: String? = null)
@Serializable
data class ConfigCustomPlaylist(val playlist: ConfigPlaylistEntry, val tracks: List<ConfigTrack> = emptyList(), @SerialName("union_sources") val unionSources: List<ConfigUnionSource> = emptyList())
@Serializable
data class ConfigPlaylistEntry(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("track_count") val trackCount: Int,
    @SerialName("playlist_type") val playlistType: String,
    @SerialName("is_distinct") val isDistinct: Boolean = false,
)
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
    @SerialName("image_url") val imageUrl: String? = null,
)
@Serializable
data class ConfigUnionSource(
    val id: Int,
    @SerialName("union_playlist_id") val unionPlaylistId: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("source_playlist_id") val sourcePlaylistId: String,
    val position: Int,
    @SerialName("added_at") val addedAt: Long,
)
internal fun String.toConfigPlaylistTypeOrNull() = when (trim().lowercase(Locale.ROOT)) { "standard" -> PlaylistType.STANDARD; "union" -> PlaylistType.UNION; else -> null }
internal fun String.toConfigSourceTypeOrNull() = when (trim().lowercase(Locale.ROOT)) { "spotify" -> SourceType.SPOTIFY; "jellyfin" -> SourceType.JELLYFIN; "plex" -> SourceType.PLEX; "custom" -> SourceType.CUSTOM; "all" -> SourceType.ALL; else -> null }
