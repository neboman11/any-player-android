package com.anyplayer.android.data.providers

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.SpotifyTokenProvider
import com.anyplayer.android.data.auth.StoredConnection
import com.anyplayer.android.data.net.HttpFailure
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class SpotifyClient(private val http: OkHttpClient, private val tokens: SpotifyTokenProvider, private val baseUrl: String) : ProviderClient {
    @Inject constructor(http: OkHttpClient, tokens: SpotifyTokenProvider) : this(http, tokens, "https://api.spotify.com/v1/")
    override val source = SourceType.SPOTIFY
    private suspend fun get(path: String, query: Map<String, String> = emptyMap(), explicitToken: String? = null): JsonObject {
        val url = (baseUrl.trimEnd('/') + "/" + path).toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        var token = explicitToken ?: tokens.accessToken() ?: throw HttpFailure(401, "Spotify token validation failed")
        fun request() = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json").build()
        return try { http.json(request()) } catch (e: HttpFailure) {
            if (e.status != 401 || explicitToken != null) throw e
            token = tokens.refreshAfterUnauthorized() ?: throw e
            http.json(request())
        }
    }
    override suspend fun validate(conn: StoredConnection): ValidationResult {
        val row = try { get("me", explicitToken = conn.token) } catch (e: HttpFailure) {
            throw HttpFailure(e.status, "Spotify token validation failed")
        }
        return ValidationResult(row.text("display_name") ?: row.text("id"), row.text("product") == "premium")
    }
    override suspend fun playlists(conn: StoredConnection): List<Playlist> {
        val result = mutableListOf<Playlist>()
        var offset = 0
        while (true) {
            val page = get("me/playlists", mapOf("limit" to "50", "offset" to "$offset"))
            val rows = page.array("items")
            result += rows.mapNotNull(::playlist)
            offset += 50
            if (offset >= (page.number("total") ?: Long.MAX_VALUE) || (page.text("next") == null && rows.size < 50)) break
        }
        return result
    }
    override suspend fun playlistTracks(conn: StoredConnection, playlistId: String): List<Track> {
        val id = normalizePlaylistId(source, playlistId)
        val size = conn.playlistPageSize.coerceIn(1, 100)
        var offset = 0
        val result = mutableListOf<Track>()
        while (true) {
            val page = get("playlists/$id/tracks", mapOf("market" to "from_token", "offset" to "$offset", "limit" to "$size"))
            val raw = page["items"] as? kotlinx.serialization.json.JsonArray
            result += page.array("items").mapNotNull { (it["track"] as? JsonObject)?.let(::track) }
            offset += size
            if (offset >= (page.number("total") ?: Long.MAX_VALUE) || raw.isNullOrEmpty() ||
                (page.text("next") == null && raw.size < size)) break
        }
        return result
    }
    override suspend fun search(conn: StoredConnection, query: String, type: SearchType, limit: Int): SearchResult {
        val params = mapOf("q" to query, "type" to if (type == SearchType.TRACKS) "track" else "playlist", "offset" to "0", "limit" to "${limit.coerceIn(1, 50)}")
        val page = get("search", params + if (type == SearchType.TRACKS) mapOf("market" to "from_token") else emptyMap())
        return if (type == SearchType.TRACKS) SearchResult(tracks = page.obj("tracks").array("items").mapNotNull(::track))
            else SearchResult(playlists = page.obj("playlists").array("items").mapNotNull(::playlist))
    }
    private fun image(row: JsonObject): String? {
        val images = row.array("images")
        return (images.filter { it.number("width") != null }.minByOrNull { kotlin.math.abs(it.number("width")!! - 300) }
            ?: images.firstOrNull())?.text("url")
    }
    private fun playlist(row: JsonObject): Playlist? = row.text("id")?.let {
        Playlist(it, row.text("name").orEmpty(), row.obj("owner").text("display_name") ?: "Spotify",
            (row.obj("tracks").number("total") ?: 0).toInt(), source, image(row), description = row.text("description"))
    }
    private fun track(row: JsonObject): Track? = row.text("id")?.let {
        Track(it, row.text("name").orEmpty(), row.array("artists").mapNotNull { artist -> artist.text("name") }.joinToString(", ").ifBlank { "Unknown Artist" },
            row.obj("album").text("name"), row.number("duration_ms"), source, "spotify:track:$it", image(row.obj("album")), enriched = true)
    }
}
