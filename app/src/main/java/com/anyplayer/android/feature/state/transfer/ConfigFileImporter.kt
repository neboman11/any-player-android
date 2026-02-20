package com.anyplayer.android.feature.state.transfer

import com.anyplayer.android.core.model.CustomPlaylist
import com.anyplayer.android.core.model.PlaylistTrack
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.UnionPlaylistSource
import com.anyplayer.android.core.storage.AppDatabase
import com.anyplayer.android.core.storage.dao.CustomPlaylistDao
import com.anyplayer.android.core.storage.dao.PlaylistTrackDao
import com.anyplayer.android.core.storage.dao.UnionPlaylistSourceDao
import com.anyplayer.android.core.storage.mapper.toEntity
import com.anyplayer.android.feature.auth.SecureConnectionStore
import com.anyplayer.android.feature.auth.StoredConnection
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports the any-player config file format (export_version / provider_configs / custom_playlists)
 * produced by companion apps.  Unlike [StateTransferManager] the config format carries server URLs
 * but not auth tokens, so only connection URLs are applied — existing tokens are preserved.
 */
@Singleton
class ConfigFileImporter @Inject constructor(
    private val db: AppDatabase,
    private val customPlaylistDao: CustomPlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val unionPlaylistSourceDao: UnionPlaylistSourceDao,
    private val secureConnectionStore: SecureConnectionStore,
    private val json: Json
) {
    suspend fun importFromFile(source: File, mergePolicy: MergePolicy, dryRun: Boolean = false): ImportSummary {
        val raw = source.readText()
        return importFromRaw(raw, mergePolicy, dryRun)
    }

    suspend fun importFromStream(stream: InputStream, mergePolicy: MergePolicy, dryRun: Boolean = false): ImportSummary {
        val raw = withContext(Dispatchers.IO) { stream.reader(Charsets.UTF_8).readText() }
        return importFromRaw(raw, mergePolicy, dryRun)
    }

    private suspend fun importFromRaw(raw: String, mergePolicy: MergePolicy, dryRun: Boolean): ImportSummary {
        val configFile = json.decodeFromString(ConfigFile.serializer(), raw)
        require(configFile.exportVersion == CONFIG_FILE_VERSION) {
            "Unsupported config file version: ${configFile.exportVersion}"
        }
        return importConfig(configFile, mergePolicy, dryRun)
    }

    private suspend fun importConfig(
        config: ConfigFile,
        mergePolicy: MergePolicy,
        dryRun: Boolean
    ): ImportSummary {
        val existingPlaylists = customPlaylistDao.getAll().associateBy { it.id }

        val remap = mutableMapOf<String, String>()
        var playlistsAdded = 0
        var playlistsUpdated = 0

        val importedPlaylists = config.customPlaylists.map { entry ->
            val incoming = entry.playlist.toModel()
            val existing = existingPlaylists[incoming.id]
            if (existing == null) {
                playlistsAdded += 1
                incoming
            } else {
                when (mergePolicy) {
                    MergePolicy.REPLACE_ALL,
                    MergePolicy.MERGE_PREFER_IMPORT -> {
                        playlistsUpdated += 1
                        incoming
                    }
                    MergePolicy.MERGE_KEEP_LOCAL -> {
                        val changed = existing.name != incoming.name || existing.updatedAt != incoming.updatedAt
                        if (changed) {
                            val newId = deterministicRemap(incoming.id)
                            remap[incoming.id] = newId
                            playlistsAdded += 1
                            incoming.copy(id = newId)
                        } else {
                            incoming.copy(id = existing.id)
                        }
                    }
                }
            }
        }

        // Flatten all tracks and union sources from every playlist entry.
        val allTracks = config.customPlaylists.flatMap { entry ->
            entry.tracks.map { track ->
                val resolvedPlaylistId = remap[track.playlistId] ?: track.playlistId
                track.toModel(resolvedPlaylistId)
            }
        }
        val allUnionSources = config.customPlaylists.flatMap { entry ->
            entry.unionSources.map { source ->
                val resolvedUnionId = remap[source.unionPlaylistId] ?: source.unionPlaylistId
                source.toModel(resolvedUnionId)
            }
        }

        val existingTracks = playlistTrackDao.getAll().associateBy { it.id }
        val existingUnionSources = unionPlaylistSourceDao.getAll().associateBy { it.id }

        val tracksAdded = allTracks.count { !existingTracks.containsKey(it.id) }
        val tracksUpdated = allTracks.size - tracksAdded
        val linksAdded = allUnionSources.count { !existingUnionSources.containsKey(it.id) }
        val linksUpdated = allUnionSources.size - linksAdded

        val warnings = mutableListOf<String>()
        var connectionsImported = 0
        try {
            connectionsImported = applyProviderConfigs(config, dryRun, warnings)
        } catch (e: Exception) {
            warnings += "Provider connection import error (playlists were still imported): ${e.message ?: "unknown"}"
        }

        if (!dryRun) {
            db.withTransaction {
                if (mergePolicy == MergePolicy.REPLACE_ALL) {
                    unionPlaylistSourceDao.deleteAll()
                    playlistTrackDao.deleteAll()
                    customPlaylistDao.deleteAll()
                }
                customPlaylistDao.upsert(importedPlaylists.map { it.toEntity() })
                playlistTrackDao.upsert(allTracks.map { it.toEntity() })
                unionPlaylistSourceDao.upsert(allUnionSources.map { it.toEntity() })
            }
        }

        return ImportSummary(
            playlistsAdded = playlistsAdded,
            playlistsUpdated = playlistsUpdated,
            tracksAdded = tracksAdded,
            tracksUpdated = tracksUpdated,
            unionLinksAdded = linksAdded,
            unionLinksUpdated = linksUpdated,
            connectionsImported = connectionsImported,
            connectionsSkipped = 0,
            warnings = warnings
        )
    }

    /**
     * Applies server URLs from the config's provider_configs block.  Only URLs that are non-blank
     * are applied; any existing auth token for that source is preserved.  Spotify client_id/
     * redirect_uri values are app build configuration, not user credentials, and are ignored.
     */
    private fun applyProviderConfigs(
        config: ConfigFile,
        dryRun: Boolean,
        warnings: MutableList<String>
    ): Int {
        var applied = 0
        val providers = config.providerConfigs

        fun applyUrl(source: SourceType, url: String?) {
            if (url.isNullOrBlank()) return
            if (dryRun) {
                applied += 1
                return
            }
            val existing = secureConnectionStore.read(source)
            val updated = (existing ?: StoredConnection(source = source)).copy(serverUrl = url)
            secureConnectionStore.save(updated)
            applied += 1
        }

        applyUrl(SourceType.JELLYFIN, providers.jellyfin?.baseUrl)
        applyUrl(SourceType.PLEX, providers.plex?.baseUrl)

        if (providers.spotify?.clientId != null || providers.spotify?.redirectUri != null) {
            warnings += "Spotify client_id/redirect_uri in config are app build settings — skipped"
        }

        return applied
    }

    // ── Converters ───────────────────────────────────────────────────────────────────────────────

    private fun ConfigPlaylistEntry.toModel(): CustomPlaylist = CustomPlaylist(
        id = id,
        name = name,
        description = description,
        imageUrl = imageUrl,
        createdAt = epochSecToIso(createdAt),
        updatedAt = epochSecToIso(updatedAt),
        trackCount = trackCount,
        playlistType = playlistType
    )

    /**
     * Converts a config track to the app model.  The integer row [id] from the config is not
     * globally unique so we derive a stable UUID from the natural key instead.
     */
    private fun ConfigTrack.toModel(resolvedPlaylistId: String): PlaylistTrack = PlaylistTrack(
        id = stableTrackId(resolvedPlaylistId, trackSource, trackId),
        playlistId = resolvedPlaylistId,
        trackSource = trackSource,
        trackId = trackId,
        position = position,
        addedAt = epochSecToIso(addedAt),
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        imageUrl = imageUrl
    )

    private fun ConfigUnionSource.toModel(resolvedUnionId: String): UnionPlaylistSource = UnionPlaylistSource(
        id = stableUnionSourceId(resolvedUnionId, sourceType, sourcePlaylistId),
        unionPlaylistId = resolvedUnionId,
        sourceType = sourceType,
        sourcePlaylistId = sourcePlaylistId,
        position = position,
        addedAt = epochSecToIso(addedAt)
    )

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun epochSecToIso(epochSec: Long): String = Instant.ofEpochSecond(epochSec).toString()

    /** Stable, collision-resistant UUID derived from the track's natural key. */
    private fun stableTrackId(playlistId: String, source: SourceType, trackId: String): String =
        UUID.nameUUIDFromBytes("track:$playlistId:${source.name}:$trackId".toByteArray()).toString()

    /** Stable UUID derived from the union source's natural key. */
    private fun stableUnionSourceId(unionPlaylistId: String, source: SourceType, sourcePlaylistId: String): String =
        UUID.nameUUIDFromBytes("union:$unionPlaylistId:${source.name}:$sourcePlaylistId".toByteArray()).toString()

    private fun deterministicRemap(id: String): String =
        "${id}_cfg_${UUID.nameUUIDFromBytes(id.toByteArray())}"
}
