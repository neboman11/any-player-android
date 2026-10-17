package com.anyplayer.android.data.providers

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.ConnectionStore
import com.anyplayer.android.data.auth.StoredConnection
import com.anyplayer.android.data.net.executeRetrying
import com.anyplayer.android.data.net.HttpFailure
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class JellyfinClient @Inject constructor(private val http: OkHttpClient, private val store: ConnectionStore) : ProviderClient {
    override val source = SourceType.JELLYFIN
    private val userMutex = Mutex()
    private val resolved = mutableMapOf<Pair<String?, String?>, ValidationResult>()
    private fun url(conn: StoredConnection, path: String, query: Map<String, String> = emptyMap()) =
        (conn.serverUrl.orEmpty().trimEnd('/') + "/" + path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    /**
     * Stream/artwork URLs are persisted (queue, custom playlists, caches) and key the Media3 audio
     * cache, so they are built exactly like legacy: plain concatenation onto the stored base, with
     * literal commas — no HttpUrl normalization or percent-encoding.
     */
    private fun legacyUrl(conn: StoredConnection, pathAndQuery: String) = conn.serverUrl.orEmpty().trimEnd('/') + "/" + pathAndQuery
    private fun request(conn: StoredConnection, path: String, query: Map<String, String> = emptyMap()) =
        Request.Builder().url(url(conn, path, query)).header("X-Emby-Token", conn.token.orEmpty())
            .header("X-Emby-Authorization", "MediaBrowser Token=\"${conn.token.orEmpty()}\", Client=\"AnyPlayer\", Device=\"AnyPlayer\", DeviceId=\"AnyPlayer\", Version=\"1.0.0\"").build()
    private suspend fun resolveUser(conn: StoredConnection): ValidationResult = userMutex.withLock {
        val identity = conn.serverUrl to conn.token
        resolved[identity]?.let { return@withLock it }
        val users = withContext(Dispatchers.IO) {
            http.executeRetrying(request(conn, "Users")).use {
                if (!it.isSuccessful) throw HttpFailure(it.code, "HTTP ${it.code}")
                AppJson.parseToJsonElement(it.body?.string().orEmpty()).jsonArray.mapNotNull { value -> value as? JsonObject }
            }
        }
        val preferred = conn.jellyfinUserId ?: conn.refreshToken
        val selected = users.firstOrNull { it.text("Id") == preferred } ?: users.firstOrNull()
            ?: error("No users found on Jellyfin server")
        val id = selected.text("Id") ?: error("No users found on Jellyfin server")
        store.update(source) { current ->
            if (current != null && current.serverUrl == conn.serverUrl && current.token == conn.token) current.copy(jellyfinUserId = id) else current
        }
        val result = ValidationResult(username = selected.text("Name"), jellyfinUserId = id)
        resolved[identity] = result
        result
    }
    private suspend fun user(conn: StoredConnection): String = checkNotNull(resolveUser(conn).jellyfinUserId)
    override suspend fun validate(conn: StoredConnection): ValidationResult {
        http.json(request(conn, "System/Info"))
        return resolveUser(conn)
    }
    private suspend fun items(conn: StoredConnection, uid: String, query: Map<String, String>): List<JsonObject> {
        val size = conn.playlistPageSize.coerceIn(1, 1000)
        val result = mutableListOf<JsonObject>()
        var offset = 0
        while (true) {
            val page = http.json(request(conn, "Users/$uid/Items", query + mapOf("Limit" to "$size", "StartIndex" to "$offset")))
            val rows = page.array("Items")
            result += rows
            offset += rows.size
            if (rows.isEmpty() || rows.size < size || offset >= (page.number("TotalRecordCount") ?: Long.MAX_VALUE)) break
        }
        return result
    }
    override suspend fun playlists(conn: StoredConnection): List<Playlist> = items(conn, user(conn), mapOf(
        "Filters" to "IsFolder", "Recursive" to "true", "IncludeItemTypes" to "Playlist")).mapNotNull { playlist(conn, it) }
    override suspend fun playlistTracks(conn: StoredConnection, playlistId: String): List<Track> {
        val uid = user(conn)
        return items(conn, uid, mapOf("ParentId" to playlistId, "Fields" to "AudioInfo,MediaSources,ParentId"))
            .filter { it.text("Type") == "Audio" }.mapNotNull { track(conn, uid, it) }
    }
    override suspend fun search(conn: StoredConnection, query: String, type: SearchType, limit: Int): SearchResult {
        val uid = user(conn)
        val parameters = mapOf("searchTerm" to query, "Recursive" to "true", "IncludeItemTypes" to
            if (type == SearchType.TRACKS) "Audio" else "Playlist") +
            if (type == SearchType.TRACKS) mapOf("Fields" to "AudioInfo,MediaSources") else emptyMap()
        // One page only: search shows the first [limit] matches, so never walk the whole library.
        val rows = http.json(request(conn, "Users/$uid/Items", parameters + mapOf("Limit" to "$limit", "StartIndex" to "0"))).array("Items").take(limit)
        return if (type == SearchType.TRACKS) SearchResult(tracks = rows.mapNotNull { track(conn, uid, it) })
            else SearchResult(playlists = rows.mapNotNull { playlist(conn, it) })
    }
    private fun image(conn: StoredConnection, row: JsonObject): String? {
        val album = row.text("AlbumId")
        val albumTag = row.text("AlbumPrimaryImageTag")
        val id = if (!album.isNullOrBlank() && !albumTag.isNullOrBlank()) album else row.text("Id")
        val tag = if (id == album) albumTag else row.obj("ImageTags").text("Primary")
        return if (id != null && tag != null) legacyUrl(conn, "Items/$id/Images/Primary?tag=$tag") else null
    }
    private fun playlist(conn: StoredConnection, row: JsonObject): Playlist? = row.text("Id")?.let {
        Playlist(it, row.text("Name").orEmpty(), "Jellyfin", (row.number("ChildCount") ?: row.number("RecursiveItemCount") ?: 0).toInt(), source, image(conn, row))
    }
    private fun track(conn: StoredConnection, uid: String, row: JsonObject): Track? = row.text("Id")?.let { id ->
        val media = row.array("MediaSources").firstOrNull()
        val stream = (media?.array("MediaStreams").orEmpty() + row.array("MediaStreams")).firstOrNull { it.text("Type") == "Audio" }
        val artists = (row["Artists"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        Track(id, row.text("Name").orEmpty(), artists.joinToString(", ").ifBlank { "Unknown Artist" },
            row.text("Album") ?: "Unknown Album", row.number("RunTimeTicks")?.div(10_000)?.takeIf { it > 0 }, source,
            legacyUrl(conn, "Audio/$id/universal?UserId=$uid&Container=$STREAM_CONTAINERS&AudioCodec=$STREAM_CODECS"),
            image(conn, row), (stream?.number("BitRate") ?: media?.number("Bitrate"))?.div(1000)?.toInt(),
            stream?.number("SampleRate")?.toInt(), enriched = true)
    }
    private companion object {
        const val STREAM_CONTAINERS = "opus,mp3,aac,m4a,flac,webma,webm,wav,ogg"
        const val STREAM_CODECS = "aac,mp3,vorbis,opus"
    }
}
