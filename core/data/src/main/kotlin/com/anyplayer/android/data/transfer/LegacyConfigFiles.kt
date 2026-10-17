package com.anyplayer.android.data.transfer

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.ConnectionStore
import com.anyplayer.android.data.auth.StoredConnection
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

@Singleton
class LegacyConfigFiles @Inject constructor(
    private val importer: PlaylistImporter,
    private val connections: ConnectionStore,
) : ConfigFiles {
    // Sync deliberately uses explicit nulls, unlike the state-file canonical encoder.
    private val syncJson = Json { ignoreUnknownKeys = true }
    override suspend fun import(input: InputStream, policy: MergePolicy, dryRun: Boolean): ImportSummary = withContext(Dispatchers.IO) {
        val raw = input.reader(Charsets.UTF_8).use { it.readText() }
        importConfig(syncJson.decodeFromString<ConfigFile>(raw), policy, dryRun)
    }
    override suspend fun importJson(config: JsonElement, policy: MergePolicy, dryRun: Boolean): ImportSummary = withContext(Dispatchers.IO) {
        importConfig(syncJson.decodeFromJsonElement<ConfigFile>(config), policy, dryRun)
    }

    private suspend fun importConfig(config: ConfigFile, policy: MergePolicy, dryRun: Boolean): ImportSummary {
        require(config.exportVersion == CONFIG_EXPORT_VERSION) { "Unsupported config file version: ${config.exportVersion}" }
        val warnings = mutableListOf<String>()
        val playlists = config.customPlaylists.map { entry ->
            val p = entry.playlist
            val type = p.playlistType.toConfigPlaylistTypeOrNull()
            if (type == null) warnings += "Unknown playlist_type '${p.playlistType}' for playlist '${p.id}'; defaulted to 'standard'"
            CustomPlaylist(p.id, p.name, p.description, p.imageUrl, iso(p.createdAt), iso(p.updatedAt), p.trackCount, type ?: PlaylistType.STANDARD, p.isDistinct)
        }
        val occurrences = HashMap<String, Int>()
        val tracks = config.customPlaylists.flatMap { it.tracks }.mapNotNull { t ->
            val source = t.trackSource.toConfigSourceTypeOrNull()
            if (source == null) { warnings += "Unknown track_source '${t.trackSource}' for track '${t.trackId}'; skipped"; null }
            else {
                val key = "track:${t.playlistId}:${source.name}:${t.trackId}"
                val occurrence = occurrences[key] ?: 0
                occurrences[key] = occurrence + 1
                // First occurrence retains the legacy natural-key id; duplicates need their own stable row id.
                val id = stableId(if (occurrence == 0) key else "$key:occurrence:$occurrence")
                PlaylistTrack(id, t.playlistId, source, t.trackId, t.position, iso(t.addedAt), t.title, t.artist, t.album, t.durationMs, t.imageUrl)
            }
        }
        val sourceOccurrences = HashMap<String, Int>()
        val sources = config.customPlaylists.flatMap { it.unionSources }.mapNotNull { s ->
            val source = s.sourceType.toConfigSourceTypeOrNull()
            if (source == null) { warnings += "Unknown source_type '${s.sourceType}' for union source '${s.sourcePlaylistId}'; skipped"; null }
            else {
                val key = "union:${s.unionPlaylistId}:${source.name}:${s.sourcePlaylistId}"
                val occurrence = sourceOccurrences[key] ?: 0
                sourceOccurrences[key] = occurrence + 1
                UnionPlaylistSource(stableId(if (occurrence == 0) key else "$key:occurrence:$occurrence"), s.unionPlaylistId, source, s.sourcePlaylistId, s.position, iso(s.addedAt))
            }
        }
        val summary = importer.import(AnyPlayerStateData(playlists, tracks, sources), policy, dryRun, "cfg", validateCustomReferences = false)
        var imported = 0
        if (config.providerConfigs.spotify?.clientId != null || config.providerConfigs.spotify?.redirectUri != null)
            warnings += "Spotify client_id/redirect_uri in config are app build settings — skipped"
        try {
            for ((source, url) in listOf(SourceType.JELLYFIN to config.providerConfigs.jellyfin?.baseUrl, SourceType.PLEX to config.providerConfigs.plex?.baseUrl)) {
                if (url.isNullOrBlank()) continue
                if (!dryRun) connections.update(source) { (it ?: StoredConnection(source)).copy(serverUrl = url) }
                imported++
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { warnings += "Provider connection import error (playlists were still imported): ${error.message ?: "unknown"}" }
        return summary.copy(connectionsImported = imported, warnings = warnings)
    }

    override suspend fun exportPlaylistsJson(): JsonElement = withContext(Dispatchers.IO) {
        val data = importer.snapshot()
        val tracks = data.playlistTracks.groupBy { it.playlistId }
        val sources = data.unionPlaylistSources.groupBy { it.unionPlaylistId }
        var trackId = 0
        var sourceId = 0
        val entries = data.customPlaylists.map { p ->
            ConfigCustomPlaylist(ConfigPlaylistEntry(p.id, p.name, p.description, p.imageUrl, epoch(p.createdAt), epoch(p.updatedAt), p.trackCount, p.playlistType.name.lowercase(), p.isDistinct),
                tracks[p.id].orEmpty().sortedBy { it.position }.map { t -> ConfigTrack(++trackId, t.playlistId, t.trackSource.name.lowercase(), t.trackId, t.position, epoch(t.addedAt), t.title, t.artist, t.album, t.durationMs, t.imageUrl) },
                sources[p.id].orEmpty().sortedBy { it.position }.map { s -> ConfigUnionSource(++sourceId, s.unionPlaylistId, s.sourceType.name.lowercase(), s.sourcePlaylistId, s.position, epoch(s.addedAt)) })
        }
        syncJson.encodeToJsonElement(entries)
    }
    override suspend fun exportProviderConfigJson(): JsonElement = withContext(Dispatchers.IO) {
        val stored = connections.awaitLoaded()
        syncJson.encodeToJsonElement(ConfigProviderConfigs(
            jellyfin = stored[SourceType.JELLYFIN]?.serverUrl?.takeIf { it.isNotBlank() }?.let { ConfigJellyfin(it) },
            plex = stored[SourceType.PLEX]?.serverUrl?.takeIf { it.isNotBlank() }?.let { ConfigPlex(it) }))
    }
    private fun stableId(key: String) = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
    private fun iso(seconds: Long) = Instant.ofEpochSecond(seconds).toString()
    private fun epoch(iso: String) = Instant.parse(iso).epochSecond
}
