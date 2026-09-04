package com.anyplayer.android.feature.state.transfer

import com.anyplayer.android.core.model.CustomPlaylist
import com.anyplayer.android.core.model.PlaylistTrack
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.UnionPlaylistSource
import com.anyplayer.android.core.storage.dao.CustomPlaylistDao
import com.anyplayer.android.core.storage.dao.PlaylistTrackDao
import com.anyplayer.android.core.storage.dao.UnionPlaylistSourceDao
import com.anyplayer.android.core.storage.mapper.toModel
import com.anyplayer.android.feature.auth.SecureConnectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the any-player config file format (export_version / provider_configs /
 * custom_playlists) from current local state - the reverse of [ConfigFileImporter].
 * Used to push local playlists/provider server URLs to the sync server the first time
 * a device connects to an empty server, so [ConfigFileImporter] can read them back
 * unchanged on any other device.
 */
@Singleton
class ConfigFileExporter @Inject constructor(
    private val customPlaylistDao: CustomPlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val unionPlaylistSourceDao: UnionPlaylistSourceDao,
    private val secureConnectionStore: SecureConnectionStore
) {
    suspend fun buildConfigFile(): ConfigFile = withContext(Dispatchers.IO) {
        val playlists = customPlaylistDao.getAll().map { it.toModel() }
        val tracksByPlaylist = playlistTrackDao.getAll().map { it.toModel() }.groupBy { it.playlistId }
        val unionSourcesByPlaylist = unionPlaylistSourceDao.getAll().map { it.toModel() }.groupBy { it.unionPlaylistId }

        var trackIdCounter = 0
        var unionIdCounter = 0

        val customPlaylists = playlists.map { playlist ->
            ConfigCustomPlaylist(
                playlist = playlist.toConfigEntry(),
                tracks = tracksByPlaylist[playlist.id].orEmpty().map { track ->
                    track.toConfigTrack(id = ++trackIdCounter)
                },
                unionSources = unionSourcesByPlaylist[playlist.id].orEmpty().map { source ->
                    source.toConfigUnionSource(id = ++unionIdCounter)
                }
            )
        }

        ConfigFile(
            exportVersion = CONFIG_EXPORT_VERSION,
            providerConfigs = ConfigProviderConfigs(
                jellyfin = secureConnectionStore.read(SourceType.JELLYFIN)?.serverUrl
                    ?.takeIf { it.isNotBlank() }?.let { ConfigJellyfin(baseUrl = it) },
                plex = secureConnectionStore.read(SourceType.PLEX)?.serverUrl
                    ?.takeIf { it.isNotBlank() }?.let { ConfigPlex(baseUrl = it) }
            ),
            customPlaylists = customPlaylists
        )
    }

    private fun CustomPlaylist.toConfigEntry(): ConfigPlaylistEntry = ConfigPlaylistEntry(
        id = id,
        name = name,
        description = description,
        imageUrl = imageUrl,
        createdAt = Instant.parse(createdAt).epochSecond,
        updatedAt = Instant.parse(updatedAt).epochSecond,
        trackCount = trackCount,
        playlistType = playlistType.name.lowercase(),
        isDistinct = isDistinct
    )

    private fun PlaylistTrack.toConfigTrack(id: Int): ConfigTrack = ConfigTrack(
        id = id,
        playlistId = playlistId,
        trackSource = trackSource.name.lowercase(),
        trackId = trackId,
        position = position,
        addedAt = Instant.parse(addedAt).epochSecond,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        imageUrl = imageUrl
    )

    private fun UnionPlaylistSource.toConfigUnionSource(id: Int): ConfigUnionSource = ConfigUnionSource(
        id = id,
        unionPlaylistId = unionPlaylistId,
        sourceType = sourceType.name.lowercase(),
        sourcePlaylistId = sourcePlaylistId,
        position = position,
        addedAt = Instant.parse(addedAt).epochSecond
    )
}
