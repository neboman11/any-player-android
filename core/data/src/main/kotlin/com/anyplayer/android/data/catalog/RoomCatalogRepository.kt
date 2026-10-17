package com.anyplayer.android.data.catalog

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.ConnectionStore
import com.anyplayer.android.data.db.AppDatabase
import com.anyplayer.android.data.db.dao.providerOwnerKey
import com.anyplayer.android.data.db.entity.*
import com.anyplayer.android.data.providers.ProviderRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString

internal fun CachedPlaylistEntity.model() = Playlist(id, name, owner, trackCount, source, imageUrl, description = description)
internal fun Playlist.entity(position: Int, timestamp: Long) = CachedPlaylistEntity(source, id, name, owner, trackCount, imageUrl, description, position, timestamp)

@Singleton
class RoomCatalogRepository @Inject constructor(
    db: AppDatabase,
    private val connections: ConnectionStore,
    private val providers: ProviderRegistry,
) : CatalogRepository {
    private val dao = db.catalogCacheDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val trackLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val sourceOrder = listOf(SourceType.JELLYFIN, SourceType.PLEX, SourceType.SPOTIFY)
    override val playlists: Flow<List<Playlist>> = dao.observePlaylists().map { rows -> rows.map { it.model() } }.flowOn(Dispatchers.IO)
    override suspend fun refreshPlaylists(): List<ProviderRefreshFailure> = refreshMutex.withLock { supervisorScope {
        val saved = connections.awaitLoaded()
        sourceOrder.map { source -> async(Dispatchers.IO) {
            val conn = saved[source] ?: return@async null
            if (conn.token.isNullOrBlank()) return@async null
            try {
                val rows = providers[source]?.playlists(conn).orEmpty()
                dao.replacePlaylists(source, rows.mapIndexed { index, playlist -> playlist.entity(index, System.currentTimeMillis()) })
                null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ProviderRefreshFailure(source, e.message.orEmpty()) }
        } }.awaitAll().filterNotNull()
    } }
    override suspend fun cachedTracks(source: SourceType, playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        dao.tracks(providerOwnerKey(source, normalizePlaylistId(source, playlistId))).map { AppJson.decodeFromString<Track>(it.trackJson) }
    }
    override suspend fun tracks(source: SourceType, playlistId: String, forceRefresh: Boolean): List<Track> = withContext(Dispatchers.IO) {
        val id = normalizePlaylistId(source, playlistId)
        val key = providerOwnerKey(source, id)
        val owner = dao.owner(key)
        val cached = cachedTracks(source, id)
        if (!forceRefresh && owner != null) {
            if (System.currentTimeMillis() - owner.updatedAtMs >= 6 * 60 * 60 * 1000L) {
                scope.launch {
                    try { fetchTracks(source, id, owner.updatedAtMs) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Keep stale cache available offline. */ }
                }
            }
            cached
        } else fetchTracks(source, id)
    }
    private suspend fun fetchTracks(source: SourceType, id: String, staleTimestamp: Long? = null): List<Track> {
        val key = providerOwnerKey(source, id)
        return trackLocks.getOrPut(key) { Mutex() }.withLock {
            if (staleTimestamp != null && dao.owner(key)?.updatedAtMs != staleTimestamp) return@withLock cachedTracks(source, id)
            val conn = connections.awaitLoaded()[source] ?: return@withLock cachedTracks(source, id)
            val provider = providers[source] ?: return@withLock emptyList()
            val tracks = provider.playlistTracks(conn, id)
            dao.replaceProviderTracks(source, id, CachedTrackOwnerEntity(key, System.currentTimeMillis(), tracks.size),
                tracks.mapIndexed { position, track -> CachedTrackEntity(key, position, AppJson.encodeToString(track)) })
            tracks
        }
    }
    override suspend fun refreshAll(onProgress: (String) -> Unit): List<ProviderRefreshFailure> = supervisorScope {
        onProgress("Fetching playlists from providers...")
        val failures = refreshPlaylists().toMutableList()
        val rows = dao.playlists()
        if (rows.isEmpty()) { onProgress("No playlists found, loading from cache..."); return@supervisorScope failures }
        val semaphore = Semaphore(4)
        val progressMutex = Mutex()
        var count = 0
        val trackFailures = rows.map { playlist -> async(Dispatchers.IO) { semaphore.withPermit {
            progressMutex.withLock {
                count++
                onProgress("Fetching tracks for \"${playlist.name}\" ($count/${rows.size}) from ${playlist.source.name}...")
            }
            try { tracks(playlist.source, playlist.id, true); null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { ProviderRefreshFailure(playlist.source, e.message.orEmpty()) }
        } } }.awaitAll().filterNotNull()
        failures += trackFailures
        onProgress("Saving playlist data...")
        onProgress("Playlist refresh complete!")
        failures
    }
    override suspend fun search(query: String, source: SourceType, type: SearchType): SearchResult = supervisorScope {
        if (query.isBlank()) return@supervisorScope SearchResult()
        val saved = connections.awaitLoaded()
        val results = sourceOrder.filter { source == SourceType.ALL || source == it }.map { provider -> async(Dispatchers.IO) {
            val conn = saved[provider] ?: return@async SearchResult()
            try { providers[provider]?.search(conn, query.trim(), type, 100) ?: SearchResult() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { SearchResult() }
        } }.awaitAll()
        SearchResult(results.flatMap { it.tracks }, results.flatMap { it.playlists })
    }
    override suspend fun clearCache() { dao.clearProviders() }
    override suspend fun isDistinct(source: SourceType, playlistId: String) = dao.preference(source.name, normalizePlaylistId(source, playlistId))?.isDistinct ?: false
    override suspend fun setDistinct(source: SourceType, playlistId: String, distinct: Boolean) {
        dao.putPreference(ProviderPlaylistPreferenceEntity(source.name, normalizePlaylistId(source, playlistId), distinct))
    }
}
