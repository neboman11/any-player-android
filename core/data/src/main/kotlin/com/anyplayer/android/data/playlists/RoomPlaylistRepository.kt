package com.anyplayer.android.data.playlists

import androidx.room.withTransaction
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.catalog.CatalogRepository
import com.anyplayer.android.data.db.AppDatabase
import com.anyplayer.android.data.db.entity.*
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Singleton
class RoomPlaylistRepository @Inject constructor(
    private val db: AppDatabase,
    private val catalog: CatalogRepository,
) : PlaylistRepository, PlaylistSourceEditor {
    private val dao = db.customPlaylistDao()
    private val cache = db.unionCacheDao()
    // Serializes source edits with materialization, so an older fetch cannot overwrite a newer edit.
    private val mutations = Mutex()
    private val fetches = Semaphore(4)
    override val customPlaylists = dao.observeAll().map { rows -> rows.map { it.toModel() } }
    override fun observePlaylist(id: String) = dao.observe(id).map { it?.toModel() }
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeTracks(id: String): Flow<List<Track>> = dao.observe(id).flatMapLatest { playlist ->
        when (playlist?.playlistType) {
            PlaylistType.STANDARD -> dao.observeTracks(id).map { rows -> rows.map { it.toTrack() } }
            PlaylistType.UNION -> cache.observe("u:$id").map { rows -> rows.map { AppJson.decodeFromString<Track>(it.trackJson) } }
            null -> flowOf(emptyList())
        }
    }.flowOn(Dispatchers.Default)
    override fun observeUnionSources(id: String) = dao.observeSources(id).map { rows -> rows.map { it.toModel() } }

    override suspend fun create(name: String, type: PlaylistType): CustomPlaylist = mutations.withLock {
        val now = Instant.now().toString()
        val playlist = CustomPlaylist(UUID.randomUUID().toString(), name.ifBlank { "Untitled Playlist" }, createdAt = now, updatedAt = now, trackCount = 0, playlistType = type)
        dao.upsert(playlist.toEntity())
        playlist
    }
    override suspend fun rename(id: String, name: String) = mutations.withLock {
        dao.rename(id, name.takeUnless { it.isBlank() }.orEmpty(), Instant.now().toString())
    }
    override suspend fun delete(id: String) = mutations.withLock { dao.delete(id) }
    override suspend fun setDistinct(id: String, distinct: Boolean) = mutations.withLock { dao.setDistinct(id, distinct, Instant.now().toString()) }
    override suspend fun addTrack(playlistId: String, track: Track) = mutations.withLock {
        val now = Instant.now().toString()
        dao.append(PlaylistTrackEntity(UUID.randomUUID().toString(), playlistId, track.source, track.id, 0, now, track.title, track.artist, track.album, track.durationMs, track.imageUrl, track.url), now)
    }
    override suspend fun removeTrackAt(playlistId: String, index: Int) = mutations.withLock { dao.removeAt(playlistId, index, Instant.now().toString()) }
    override suspend fun reorderTracks(playlistId: String, orderedTrackIds: List<String>) = mutations.withLock { dao.reorder(playlistId, orderedTrackIds, Instant.now().toString()) }

    override suspend fun replaceUnionSources(unionId: String, sources: List<Pair<SourceType, String>>) = withContext(Dispatchers.IO) {
        mutations.withLock {
            if (dao.get(unionId)?.playlistType != PlaylistType.UNION) return@withLock
            val now = Instant.now().toString()
            val rows = sources.mapIndexed { position, (source, id) -> UnionPlaylistSourceEntity(UUID.randomUUID().toString(), unionId, source, normalizePlaylistId(source, id), position, now) }
            val tracks = fetchSources(rows, false, 1) {}
            db.withTransaction {
                dao.replaceSources(unionId, rows)
                saveMaterialization(unionId, tracks)
            }
        }
    }

    override suspend fun addUnionSource(unionId: String, source: SourceType, sourcePlaylistId: String) = editSources(unionId) { rows ->
        rows + UnionPlaylistSourceEntity(UUID.randomUUID().toString(), unionId, source, normalizePlaylistId(source, sourcePlaylistId),
            dao.nextSourcePosition(unionId), Instant.now().toString())
    }

    override suspend fun removeUnionSource(unionId: String, sourceId: String) = editSources(unionId) { rows ->
        rows.filterNot { it.id == sourceId }.mapIndexed { index, row -> row.copy(position = index) }
    }

    override suspend fun reorderUnionSources(unionId: String, orderedSourceIds: List<String>) = editSources(unionId) { rows ->
        val byId = rows.associateBy { it.id }
        val requested = orderedSourceIds.distinct().mapNotNull(byId::get)
        val requestedIds = requested.mapTo(HashSet()) { it.id }
        (requested + rows.filter { it.id !in requestedIds }).mapIndexed { index, row -> row.copy(position = index) }
    }

    private suspend fun editSources(id: String, edit: suspend (List<UnionPlaylistSourceEntity>) -> List<UnionPlaylistSourceEntity>) = withContext(Dispatchers.IO) {
        mutations.withLock {
            if (dao.get(id)?.playlistType != PlaylistType.UNION) return@withLock
            val rows = edit(dao.sources(id))
            val tracks = fetchSources(rows, false, 1) {}
            db.withTransaction {
                dao.replaceSources(id, rows)
                saveMaterialization(id, tracks)
            }
        }
    }

    override suspend fun materializeUnion(unionId: String, forceRefresh: Boolean, onProgress: (String) -> Unit): List<Track> = withContext(Dispatchers.IO) {
        mutations.withLock {
            if (dao.get(unionId)?.playlistType != PlaylistType.UNION) return@withLock emptyList()
            val tracks = fetchSources(dao.sources(unionId), forceRefresh, 1, onProgress)
            onProgress("Finalizing...")
            db.withTransaction { saveMaterialization(unionId, tracks) }
            tracks
        }
    }

    private suspend fun fetchSources(rows: List<UnionPlaylistSourceEntity>, force: Boolean, nesting: Int, progress: (String) -> Unit): List<Track> = coroutineScope {
        rows.mapIndexed { index, source -> async {
            val label = when (source.sourceType) {
                SourceType.CUSTOM -> "custom playlist"
                SourceType.SPOTIFY -> "Spotify"
                SourceType.JELLYFIN -> "Jellyfin"
                SourceType.PLEX -> "Plex"
                SourceType.ALL -> "all"
            }
            progress("Processing source ${index + 1}/${rows.size} from $label...")
            try {
                when (source.sourceType) {
                    SourceType.CUSTOM -> {
                        if (nesting > 0 && dao.get(source.sourcePlaylistId)?.playlistType == PlaylistType.UNION)
                            fetchSources(dao.sources(source.sourcePlaylistId), force, nesting - 1) {}
                        else dao.tracks(source.sourcePlaylistId).map { it.toTrack() }
                    }
                    SourceType.ALL -> emptyList()
                    else -> fetches.withPermit { catalog.tracks(source.sourceType, normalizePlaylistId(source.sourceType, source.sourcePlaylistId), force) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        } }.awaitAll().flatten().distinctBy { it.source to it.id }.map { it.copy(enriched = it.enriched ?: true) }
    }

    private suspend fun saveMaterialization(id: String, tracks: List<Track>) {
        // A concurrent import may have removed the playlist while provider requests were pending.
        if (dao.get(id)?.playlistType != PlaylistType.UNION) return
        cache.replace(CachedTrackOwnerEntity("u:$id", System.currentTimeMillis(), tracks.size), tracks.mapIndexed { index, track -> CachedTrackEntity("u:$id", index, AppJson.encodeToString(track)) })
        dao.materialized(id, tracks.size, Instant.now().toString())
    }

    override suspend fun playbackSelection(id: String, fromIndex: Int?): PlaybackSelection = withContext(Dispatchers.Default) {
        val playlist = dao.get(id) ?: return@withContext PlaybackSelection(emptyList(), 0)
        val tracks = if (playlist.playlistType == PlaylistType.UNION) materializeUnion(id) else dao.tracks(id).map { it.toTrack() }
        if (!playlist.isDistinct) return@withContext PlaybackSelection(tracks, (fromIndex ?: 0).takeIf { it in tracks.indices } ?: 0)
        val distinct = Dedupe.deduplicateTracks(tracks).tracks
        val key = fromIndex?.let { tracks.getOrNull(it) }?.let { Dedupe.key(it.title, it.artist) }
        val index = if (key == null) 0 else distinct.indexOfFirst { Dedupe.key(it.title, it.artist) == key }.coerceAtLeast(0)
        PlaybackSelection(distinct, index)
    }
}
