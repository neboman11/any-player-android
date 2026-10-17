package com.anyplayer.android.data.providers

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.StoredConnection
import com.anyplayer.android.data.net.HttpFailure
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class PlexClient @Inject constructor(private val http: OkHttpClient) : ProviderClient {
    override val source = SourceType.PLEX
    private fun url(conn: StoredConnection, path: String, query: Map<String, String> = emptyMap()): String =
        (conn.serverUrl.orEmpty().trimEnd('/') + "/" + path.trimStart('/')).toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
            addQueryParameter("X-Plex-Token", conn.token.orEmpty())
        }.build().toString()
    private suspend fun get(conn: StoredConnection, path: String, query: Map<String, String> = emptyMap()) =
        http.json(Request.Builder().url(url(conn, path, query)).header("Accept", "application/json").build()).obj("MediaContainer")
    override suspend fun validate(conn: StoredConnection): ValidationResult {
        try { get(conn, "identity") } catch (e: HttpFailure) {
            throw HttpFailure(e.status, "Plex authentication failed: HTTP ${e.status}")
        }
        return ValidationResult()
    }
    private suspend fun pages(conn: StoredConnection, path: String, query: Map<String, String> = emptyMap()): List<JsonObject> {
        val size = conn.playlistPageSize.coerceIn(1, 1000)
        var offset = 0
        val result = mutableListOf<JsonObject>()
        while (true) {
            val page = get(conn, path, query + mapOf("containerStart" to "$offset", "containerSize" to "$size"))
            val rows = page.array("Metadata")
            result += rows
            offset += rows.size
            if (rows.isEmpty() || rows.size < size || offset >= (page.number("totalSize") ?: Long.MAX_VALUE)) break
        }
        return result
    }
    override suspend fun playlists(conn: StoredConnection): List<Playlist> = pages(conn, "playlists/all", mapOf("type" to "15")).mapNotNull { playlist(conn, it) }
    override suspend fun playlistTracks(conn: StoredConnection, playlistId: String): List<Track> =
        pages(conn, "playlists/$playlistId/items").mapNotNull { track(conn, it) }
    override suspend fun search(conn: StoredConnection, query: String, type: SearchType, limit: Int): SearchResult {
        val params = if (type == SearchType.TRACKS) mapOf("containerStart" to "0", "containerSize" to "${limit.coerceAtMost(50)}")
            else mapOf("limit" to "${limit.coerceAtMost(50)}")
        val rows = get(conn, "search", params + ("query" to query)).array("Metadata")
        return if (type == SearchType.TRACKS) SearchResult(tracks = rows.filter { it.text("type") == "track" || it.number("type") == 10L }.take(limit).mapNotNull { track(conn, it) })
        else SearchResult(playlists = rows.filter { it.text("type") == "playlist" || it.number("type") == 15L }.take(limit).mapNotNull { playlist(conn, it) })
    }
    private fun image(conn: StoredConnection, row: JsonObject) = row.text("thumb")?.let { url(conn, it) }
    private fun playlist(conn: StoredConnection, row: JsonObject): Playlist? = row.text("ratingKey")?.let {
        Playlist(it, row.text("title").orEmpty(), "Plex", (row.number("leafCount") ?: 0).toInt(), source, image(conn, row), description = row.text("summary"))
    }
    private fun track(conn: StoredConnection, row: JsonObject): Track? = row.text("ratingKey")?.let {
        val media = row.array("Media").firstOrNull()
        Track(it, row.text("title").orEmpty(), row.text("grandparentTitle") ?: "Unknown Artist",
            row.text("parentTitle") ?: "Unknown Album", row.number("duration")?.takeIf { duration -> duration > 0 }, source,
            media?.array("Part")?.firstOrNull()?.text("key")?.let { key -> url(conn, key) }, image(conn, row),
            media?.number("bitrate")?.toInt(), media?.array("Part")?.firstOrNull()?.array("Stream")
                ?.firstOrNull { stream -> stream.number("streamType") == 2L }?.number("samplingRate")?.toInt(), enriched = true)
    }
}
