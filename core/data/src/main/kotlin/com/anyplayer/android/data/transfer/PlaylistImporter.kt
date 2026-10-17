package com.anyplayer.android.data.transfer

import androidx.room.withTransaction
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.db.AppDatabase
import com.anyplayer.android.data.db.entity.ColumnPreferenceEntity
import com.anyplayer.android.data.playlists.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal data class PlaylistImportPlan(val data: AnyPlayerStateData, val summary: ImportSummary)

internal fun planPlaylistImport(
    incoming: AnyPlayerStateData,
    existingPlaylists: List<CustomPlaylist>,
    existingTracks: List<PlaylistTrack>,
    existingSources: List<UnionPlaylistSource>,
    policy: MergePolicy,
    suffix: String,
    validateCustomReferences: Boolean = true,
): PlaylistImportPlan {
    val existing = existingPlaylists.associateBy { it.id }
    val remap = HashMap<String, String>()
    var added = 0
    var updated = 0
    fun remapped(id: String) = "${id}_${suffix}_${UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8))}"
    val playlists = incoming.customPlaylists.map { playlist ->
        val local = existing[playlist.id]
        when {
            local == null -> { added++; playlist }
            policy != MergePolicy.MERGE_KEEP_LOCAL -> { updated++; playlist }
            local.name != playlist.name || local.updatedAt != playlist.updatedAt -> {
                val id = remapped(playlist.id)
                remap[playlist.id] = id
                added++
                playlist.copy(id = id)
            }
            else -> playlist
        }
    }
    val oldTracks = existingTracks.associateBy { it.id }
    val oldSources = existingSources.associateBy { it.id }
    val tracks = incoming.playlistTracks.map { track ->
        val parent = remap[track.playlistId] ?: track.playlistId
        val id = if (parent != track.playlistId || oldTracks[track.id]?.playlistId?.let { it != parent } == true)
            remapped("${track.id}:$parent") else track.id
        track.copy(id = id, playlistId = parent)
    }
    val sources = incoming.unionPlaylistSources.map { source ->
        val parent = remap[source.unionPlaylistId] ?: source.unionPlaylistId
        val id = if (parent != source.unionPlaylistId || oldSources[source.id]?.unionPlaylistId?.let { it != parent } == true)
            remapped("${source.id}:$parent") else source.id
        source.copy(id = id, unionPlaylistId = parent,
            sourcePlaylistId = if (source.sourceType == SourceType.CUSTOM) remap[source.sourcePlaylistId] ?: source.sourcePlaylistId else source.sourcePlaylistId)
    }
    // Desktop/server payloads may carry colliding or gapped positions; legacy (REPLACE) tolerated
    // them, so keep each playlist's order (stable) and renumber 0..n-1 instead of aborting.
    val orderedTracks = tracks.groupBy { it.playlistId }.values.flatMap { rows -> rows.sortedBy { it.position }.mapIndexed { index, row -> row.copy(position = index) } }
    val orderedSources = sources.groupBy { it.unionPlaylistId }.values.flatMap { rows -> rows.sortedBy { it.position }.mapIndexed { index, row -> row.copy(position = index) } }
    val ids = playlists.mapTo(HashSet()) { it.id }
    tracks.forEach { require(it.playlistId in ids) { "Malformed reference: track ${it.id} points to missing playlist ${it.playlistId}" } }
    sources.forEach { require(it.unionPlaylistId in ids && (!validateCustomReferences || it.sourceType != SourceType.CUSTOM || it.sourcePlaylistId in ids)) { "Malformed union source reference: ${it.id}" } }
    val tracksAdded = tracks.count { it.id !in oldTracks }
    val sourcesAdded = sources.count { it.id !in oldSources }
    return PlaylistImportPlan(incoming.copy(customPlaylists = playlists, playlistTracks = orderedTracks, unionPlaylistSources = orderedSources),
        ImportSummary(playlistsAdded = added, playlistsUpdated = updated, tracksAdded = tracksAdded,
            tracksUpdated = tracks.size - tracksAdded, unionSourcesAdded = sourcesAdded, unionSourcesUpdated = sources.size - sourcesAdded))
}

/** Storage boundary permits JVM testing of the same importer used by Room, state files and sync. */
internal interface PlaylistTransferStore {
    suspend fun <T> transaction(block: suspend () -> T): T
    suspend fun snapshot(): AnyPlayerStateData
    suspend fun apply(data: AnyPlayerStateData, replaceAll: Boolean)
}

internal class RoomPlaylistTransferStore(private val db: AppDatabase) : PlaylistTransferStore {
    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
    override suspend fun snapshot(): AnyPlayerStateData {
        val dao = db.customPlaylistDao()
        return AnyPlayerStateData(dao.getAll().map { it.toModel() }, dao.allTracks().map { it.toModel() }, dao.allSources().map { it.toModel() },
            dao.columns().associate { it.key to ColumnPreferences(it.key, it.visible, it.position) })
    }
    override suspend fun apply(data: AnyPlayerStateData, replaceAll: Boolean) {
        val dao = db.customPlaylistDao()
        val incomingIds = data.customPlaylists.mapTo(HashSet()) { it.id }
        // Retained playlists are overwritten in place below; only local-only ones are deleted.
        if (replaceAll) dao.getAll().filter { it.id !in incomingIds }.forEach { dao.delete(it.id) }
        val incomingSources = data.unionPlaylistSources.groupBy { it.unionPlaylistId }
        data.customPlaylists.forEach { playlist ->
            // Legacy kept union materializations across imports; drop one only when it no longer
            // describes the imported union (type or ordered source list changed).
            val keepCache = playlist.playlistType == PlaylistType.UNION && dao.get(playlist.id)?.playlistType == PlaylistType.UNION &&
                dao.sources(playlist.id).map { it.sourceType to it.sourcePlaylistId } ==
                incomingSources[playlist.id].orEmpty().sortedBy { it.position }.map { it.sourceType to it.sourcePlaylistId }
            dao.upsert(playlist.toEntity())
            dao.clearTracks(playlist.id)
            dao.clearSources(playlist.id)
            if (!keepCache) {
                dao.clearCachedTracks("u:${playlist.id}")
                dao.clearCachedOwner("u:${playlist.id}")
                dao.clearLegacyCache("union_playlist_tracks_${playlist.id}")
            }
        }
        dao.insertTracks(data.playlistTracks.map { it.toEntity() })
        dao.insertSources(data.unionPlaylistSources.map { it.toEntity() })
        dao.putColumns(data.columnPreferences.values.map { ColumnPreferenceEntity(it.key, it.visible, it.position) })
    }
}

@Singleton
class PlaylistImporter internal constructor(private val store: PlaylistTransferStore) {
    @Inject constructor(db: AppDatabase) : this(RoomPlaylistTransferStore(db))

    /** Consistent snapshot; callers encode/encrypt/write after the transaction has ended. */
    internal suspend fun snapshot(): AnyPlayerStateData = store.transaction { store.snapshot() }

    internal suspend fun import(data: AnyPlayerStateData, policy: MergePolicy, dryRun: Boolean, suffix: String, validateCustomReferences: Boolean = true): ImportSummary = store.transaction {
        val existing = store.snapshot()
        val plan = planPlaylistImport(data, existing.customPlaylists, existing.playlistTracks, existing.unionPlaylistSources, policy, suffix, validateCustomReferences)
        if (!dryRun) store.apply(plan.data, policy == MergePolicy.REPLACE_ALL)
        plan.summary
    }
}
