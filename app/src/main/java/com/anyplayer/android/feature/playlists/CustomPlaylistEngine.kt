package com.anyplayer.android.feature.playlists

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.CustomPlaylist
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.PlaylistTrack
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.UnionPlaylistSource
import com.anyplayer.android.core.storage.repository.PlaylistStorageRepository
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import com.anyplayer.android.feature.providers.ProviderCatalogRepository
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CustomPlaylistEngine"

@Singleton
class CustomPlaylistEngine @Inject constructor(
    private val storageRepository: PlaylistStorageRepository,
    private val providerCatalogRepository: ProviderCatalogRepository,
    private val playbackQueueManager: PlaybackQueueManager
) {
    fun observeCustomPlaylists(): Flow<List<CustomPlaylist>> = storageRepository.observeCustomPlaylists()

    suspend fun getUnionSources(unionPlaylistId: String): List<UnionPlaylistSource> =
        storageRepository.getUnionSources(unionPlaylistId).sortedBy { it.position }

    suspend fun createPlaylist(name: String, playlistType: PlaylistType): CustomPlaylist {
        val now = Instant.now().toString()
        val playlist = CustomPlaylist(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Untitled Playlist" },
            description = null,
            imageUrl = null,
            createdAt = now,
            updatedAt = now,
            trackCount = 0,
            playlistType = playlistType
        )
        storageRepository.upsertCustomPlaylists(listOf(playlist))
        return playlist
    }

    suspend fun renamePlaylist(id: String, newName: String) {
        val existing = storageRepository.getCustomPlaylistById(id) ?: return
        storageRepository.upsertCustomPlaylists(
            listOf(
                existing.copy(
                    name = newName.ifBlank { existing.name },
                    updatedAt = Instant.now().toString()
                )
            )
        )
    }

    suspend fun deletePlaylist(id: String) {
        storageRepository.clearCachedUnionPlaylistTracks(id)
        storageRepository.deleteCustomPlaylistById(id)
    }

    suspend fun addTrack(playlistId: String, track: Track) {
        val playlist = storageRepository.getCustomPlaylistById(playlistId) ?: return
        if (playlist.playlistType != PlaylistType.STANDARD) return

        val existingTracks = storageRepository.getPlaylistTracks(playlistId)
        val entry = PlaylistTrack(
            id = UUID.randomUUID().toString(),
            playlistId = playlistId,
            trackSource = track.source,
            trackId = track.id,
            position = existingTracks.size,
            addedAt = Instant.now().toString(),
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            imageUrl = track.imageUrl,
            url = track.url
        )

        val updatedTracks = (existingTracks + entry).reindexed()
        storageRepository.replacePlaylistTracks(playlistId, updatedTracks)
        storageRepository.upsertCustomPlaylists(
            listOf(
                playlist.copy(
                    updatedAt = Instant.now().toString(),
                    trackCount = updatedTracks.size
                )
            )
        )
    }

    suspend fun removeTrack(playlistId: String, playlistTrackId: String) {
        val playlist = storageRepository.getCustomPlaylistById(playlistId) ?: return
        val updatedTracks = storageRepository
            .getPlaylistTracks(playlistId)
            .filterNot { it.id == playlistTrackId }
            .reindexed()

        storageRepository.replacePlaylistTracks(playlistId, updatedTracks)
        storageRepository.upsertCustomPlaylists(
            listOf(
                playlist.copy(
                    updatedAt = Instant.now().toString(),
                    trackCount = updatedTracks.size
                )
            )
        )
    }

    suspend fun removeTrackAt(playlistId: String, index: Int) {
        val storedTracks = storageRepository.getPlaylistTracks(playlistId)
        val target = storedTracks.getOrNull(index) ?: return
        removeTrack(playlistId, target.id)
    }

    suspend fun reorderTracks(playlistId: String, orderedTrackIds: List<String>) {
        val playlist = storageRepository.getCustomPlaylistById(playlistId) ?: return
        val existing = storageRepository.getPlaylistTracks(playlistId)
        val byId = existing.associateBy { it.id }
        val reordered = orderedTrackIds.mapNotNull { byId[it] }.mapIndexed { index, value -> value.copy(position = index) }
        val remainder = existing.filterNot { it.id in orderedTrackIds.toSet() }
        storageRepository.replacePlaylistTracks(playlistId, (reordered + remainder).reindexed())
        storageRepository.upsertCustomPlaylists(
            listOf(
                playlist.copy(
                    updatedAt = Instant.now().toString(),
                    trackCount = existing.size
                )
            )
        )
    }

    suspend fun addUnionSource(
        unionPlaylistId: String,
        sourceType: SourceType,
        sourcePlaylistId: String
    ) {
        val playlist = storageRepository.getCustomPlaylistById(unionPlaylistId) ?: return
        if (playlist.playlistType != PlaylistType.UNION) return

        val existing = storageRepository.getUnionSources(unionPlaylistId)
        val newSource = UnionPlaylistSource(
            id = UUID.randomUUID().toString(),
            unionPlaylistId = unionPlaylistId,
            sourceType = sourceType,
            sourcePlaylistId = sourcePlaylistId,
            position = existing.size,
            addedAt = Instant.now().toString()
        )
        storageRepository.replaceUnionSources(unionPlaylistId, (existing + newSource).reindexedUnion())
        refreshUnionTrackCount(unionPlaylistId)
    }

    suspend fun removeUnionSource(unionPlaylistId: String, unionSourceId: String) {
        val updated = storageRepository
            .getUnionSources(unionPlaylistId)
            .filterNot { it.id == unionSourceId }
            .reindexedUnion()
        storageRepository.replaceUnionSources(unionPlaylistId, updated)
        refreshUnionTrackCount(unionPlaylistId)
    }

    suspend fun reorderUnionSources(unionPlaylistId: String, orderedSourceIds: List<String>) {
        val existing = storageRepository.getUnionSources(unionPlaylistId)
        val byId = existing.associateBy { it.id }
        val reordered = orderedSourceIds.mapNotNull { byId[it] }.mapIndexed { index, value -> value.copy(position = index) }
        val remainder = existing.filterNot { it.id in orderedSourceIds.toSet() }
        storageRepository.replaceUnionSources(unionPlaylistId, (reordered + remainder).reindexedUnion())
        refreshUnionTrackCount(unionPlaylistId)
    }

    /**
     * Replace the entire list of union sources for a playlist.
     * The supplied list's order determines the saved position.
     */
    suspend fun replaceUnionSources(unionPlaylistId: String, sources: List<UnionPlaylistSource>) {
        storageRepository.replaceUnionSources(unionPlaylistId, sources.reindexedUnion())
        storageRepository.clearCachedUnionPlaylistTracks(unionPlaylistId)
        refreshUnionTrackCount(unionPlaylistId)
    }

    suspend fun getCachedTracksForPlaylist(playlistId: String): List<Track> {
        val playlist = storageRepository.getCustomPlaylistById(playlistId) ?: return emptyList()
        return when (playlist.playlistType) {
            PlaylistType.STANDARD -> storageRepository.getPlaylistTracks(playlistId).map { it.toTrack() }
            PlaylistType.UNION -> storageRepository.getCachedUnionPlaylistTracks(playlistId) ?: emptyList()
        }
    }

    suspend fun materializeUnionTracks(
        unionPlaylistId: String,
        onProgressUpdate: (String) -> Unit = {},
        forceRefresh: Boolean = false,
        updateMetadata: Boolean = false
    ): List<Track> {
        val unionSources = storageRepository.getUnionSources(unionPlaylistId).sortedBy { it.position }
        val materialized = mutableListOf<Track>()
        val totalSources = unionSources.size

        unionSources.forEachIndexed { index, source ->
            val sourceLabel = when (source.sourceType) {
                SourceType.CUSTOM -> "custom playlist"
                SourceType.SPOTIFY -> "Spotify"
                SourceType.JELLYFIN -> "Jellyfin"
                SourceType.PLEX -> "Plex"
                SourceType.ALL -> "all"
            }
            onProgressUpdate("Processing source ${index + 1}/$totalSources from $sourceLabel...")
            
            val tracks = try {
                when (source.sourceType) {
                    SourceType.CUSTOM -> storageRepository.getPlaylistTracks(source.sourcePlaylistId).map { it.toTrack() }
                    SourceType.JELLYFIN,
                    SourceType.PLEX,
                    SourceType.SPOTIFY -> {
                        providerCatalogRepository.getPlaylistTracksWithCache(
                            sourceType = source.sourceType,
                            playlistId = source.sourcePlaylistId,
                            forceRefresh = forceRefresh
                        )
                    }
                    SourceType.ALL -> emptyList()
                }
            } catch (e: Exception) {
                CompatLog.e(TAG, "Failed to load tracks for union source $sourceLabel (${source.sourcePlaylistId}): ${e.message}", e)
                emptyList()
            }
            materialized += tracks
        }

        onProgressUpdate("Finalizing...")
        // Deduplicate by source and track ID to avoid same track from multiple union sources
        val deduplicatedBySourceAndId = materialized.distinctBy { Pair(it.source, it.id) }
        val result = deduplicatedBySourceAndId
            .map { track ->
                track.copy(
                    enriched = track.enriched ?: true,
                    durationMs = track.durationMs ?: 0L
                )
            }
        storageRepository.saveCachedUnionPlaylistTracks(unionPlaylistId, result)
        if (updateMetadata) {
            refreshPlaylistMetadata(unionPlaylistId, result.size)
        }
        CompatLog.d(TAG, "materializeUnionTracks END: ${result.size} tracks after deduplication")
        return result
    }

    suspend fun playPlaylist(playlistId: String) {
        val playlist = storageRepository.getCustomPlaylistById(playlistId) ?: return
        val tracks = when (playlist.playlistType) {
            PlaylistType.STANDARD -> {
                val playlistTracks = storageRepository.getPlaylistTracks(playlistId)
                if (playlist.isDistinct) {
                    DistinctPlaylistUtils.deduplicate(playlistTracks).tracks.map { it.toTrack() }
                } else {
                    playlistTracks.map { it.toTrack() }
                }
            }
            PlaylistType.UNION -> {
                val materialized = materializeUnionTracks(playlistId)
                if (playlist.isDistinct) {
                    DistinctPlaylistUtils.deduplicateTracks(materialized).tracks
                } else {
                    materialized
                }
            }
        }
        playbackQueueManager.setQueue(tracks, startIndex = 0, autoPlay = true)
    }

    suspend fun playFromTrack(playlistId: String, trackIndex: Int) {
        val playlist = storageRepository.getCustomPlaylistById(playlistId) ?: return
        when (playlist.playlistType) {
            PlaylistType.STANDARD -> {
                val playlistTracks = storageRepository.getPlaylistTracks(playlistId)
                if (playlist.isDistinct) {
                    val result = DistinctPlaylistUtils.deduplicate(playlistTracks)
                    val dedupedTracks = result.tracks.map { it.toTrack() }
                    val clickedTrack = playlistTracks.getOrNull(trackIndex)
                    val resolvedIndex = if (clickedTrack != null) {
                        val key = DistinctPlaylistUtils.buildDedupeKey(clickedTrack.title, clickedTrack.artist)
                        dedupedTracks.indexOfFirst {
                            DistinctPlaylistUtils.buildDedupeKey(it.title, it.artist) == key
                        }.takeIf { it >= 0 } ?: 0
                    } else 0
                    playbackQueueManager.setQueue(dedupedTracks, startIndex = resolvedIndex, autoPlay = true)
                } else {
                    val tracks = playlistTracks.map { it.toTrack() }
                    playbackQueueManager.setQueue(tracks, startIndex = trackIndex, autoPlay = true)
                }
            }
            PlaylistType.UNION -> {
                val materialized = materializeUnionTracks(playlistId)
                if (playlist.isDistinct) {
                    val result = DistinctPlaylistUtils.deduplicateTracks(materialized)
                    val dedupedTracks = result.tracks
                    val clickedTrack = materialized.getOrNull(trackIndex)
                    val resolvedIndex = if (clickedTrack != null) {
                        val key = DistinctPlaylistUtils.buildDedupeKey(clickedTrack.title, clickedTrack.artist)
                        dedupedTracks.indexOfFirst {
                            DistinctPlaylistUtils.buildDedupeKey(it.title, it.artist) == key
                        }.takeIf { it >= 0 } ?: 0
                    } else 0
                    playbackQueueManager.setQueue(dedupedTracks, startIndex = resolvedIndex, autoPlay = true)
                } else {
                    playbackQueueManager.setQueue(materialized, startIndex = trackIndex, autoPlay = true)
                }
            }
        }
    }

    suspend fun getTracksForPlaylist(playlistId: String): List<Track> {
        val playlist = storageRepository.getCustomPlaylistById(playlistId) ?: return emptyList()
        return when (playlist.playlistType) {
            PlaylistType.STANDARD -> storageRepository.getPlaylistTracks(playlistId).map { it.toTrack() }
            PlaylistType.UNION -> materializeUnionTracks(playlistId)
        }
    }

    private suspend fun refreshUnionTrackCount(unionPlaylistId: String) {
        materializeUnionTracks(unionPlaylistId, updateMetadata = true)
    }

    private suspend fun refreshPlaylistMetadata(playlistId: String, trackCount: Int) {
        val playlist = storageRepository.getCustomPlaylistById(playlistId) ?: return
        storageRepository.upsertCustomPlaylists(
            listOf(
                playlist.copy(
                    updatedAt = Instant.now().toString(),
                    trackCount = trackCount
                )
            )
        )
    }
}

private fun List<PlaylistTrack>.reindexed(): List<PlaylistTrack> = mapIndexed { index, track ->
    track.copy(position = index)
}

private fun List<UnionPlaylistSource>.reindexedUnion(): List<UnionPlaylistSource> = mapIndexed { index, source ->
    source.copy(position = index)
}

private fun PlaylistTrack.toTrack(): Track = Track(
    id = trackId,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    source = trackSource,
    url = url,
    imageUrl = imageUrl,
    enriched = true
)
