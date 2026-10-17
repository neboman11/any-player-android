package com.anyplayer.android.data.db

import androidx.room.withTransaction
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.catalog.entity
import com.anyplayer.android.data.db.dao.providerOwnerKey
import com.anyplayer.android.data.db.entity.*
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Singleton
class LegacyCacheImporter @Inject constructor(private val db: AppDatabase) {
    private val mutex = Mutex()
    private var completed = false
    suspend fun runOnce() = withContext(Dispatchers.IO) { mutex.withLock {
        if (completed) return@withLock
        val dao = db.catalogCacheDao()
        db.withTransaction {
            for (row in dao.legacyEntries().sortedBy { if (it.key == "provider_playlists") 0 else 1 }) {
                val timestamp = runCatching { Instant.parse(row.updatedAt).toEpochMilli() }.getOrDefault(0)
                val json = runCatching { AppJson.parseToJsonElement(row.valueJson).jsonObject }.getOrNull()
                if (json != null) {
                    suspend fun saveTracks(key: String, tracks: List<Track>) {
                        val existing = dao.owner(key)
                        if (existing == null || existing.updatedAtMs < timestamp) dao.replaceTracks(
                            CachedTrackOwnerEntity(key, timestamp, tracks.size),
                            tracks.mapIndexed { index, track -> CachedTrackEntity(key, index, AppJson.encodeToString(track)) })
                    }
                    when {
                        row.key == "provider_playlists" && row.version == 1 -> {
                            val playlists = runCatching { AppJson.decodeFromJsonElement<List<Playlist>>(json["playlists"] ?: JsonArray(emptyList())) }.getOrNull()
                            playlists?.groupBy { it.source }?.forEach { (source, rows) ->
                                if (dao.playlists(source).isEmpty()) dao.putPlaylists(rows.mapIndexed { index, playlist -> playlist.entity(index, timestamp) })
                                rows.forEach { playlist -> playlist.tracks?.let { saveTracks(providerOwnerKey(source, playlist.id), it) } }
                            }
                        }
                        row.key.startsWith("provider_playlist_tracks_") && row.version == 2 -> {
                            val source = runCatching { AppJson.decodeFromJsonElement<SourceType>(json.getValue("source")) }.getOrNull()
                            val id = (json["playlistId"] as? JsonPrimitive)?.contentOrNull
                            val tracks = runCatching { AppJson.decodeFromJsonElement<List<Track>>(json.getValue("tracks")) }.getOrNull()
                            if (source != null && id != null && tracks != null) saveTracks(providerOwnerKey(source, id), tracks)
                        }
                        row.key.startsWith("union_playlist_tracks_") -> {
                            val id = (json["unionPlaylistId"] as? JsonPrimitive)?.contentOrNull ?: row.key.removePrefix("union_playlist_tracks_")
                            val tracks = runCatching { AppJson.decodeFromJsonElement<List<Track>>(json.getValue("tracks")) }.getOrNull()
                            if (tracks != null) saveTracks("u:$id", tracks)
                        }
                    }
                }
                // Invalid or obsolete cache versions are disposable; user-owned rows are untouched.
                dao.deleteLegacy(row.key)
            }
        }
        completed = true
    } }
}
