package com.anyplayer.android.feature.auth.spotify

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** Reads playlists, playlist tracks, and search results from the Spotify Web API. */
@Singleton
class SpotifyCatalogClient @Inject constructor(
    private val spotifyApiExecutor: SpotifyApiExecutor
) {
    fun getPlaylists(accessToken: String, offset: Int = 0, limit: Int = 50): List<Playlist> {
        val root = spotifyApiExecutor.execute(
            path = "me/playlists",
            token = accessToken,
            query = mapOf(
                "offset" to offset.coerceAtLeast(0).toString(),
                "limit" to limit.coerceIn(1, 50).toString()
            )
        ) ?: return emptyList()

        val items = root["items"] as? JsonArray ?: JsonArray(emptyList())
        return items.mapNotNull { parsePlaylist(it.jsonObject) }
    }

    fun getPlaylistTracks(accessToken: String, playlistId: String, offset: Int = 0, limit: Int = 100): List<Track> =
        getPlaylistTracksPage(accessToken, playlistId, offset, limit).tracks

    /**
     * Returns a page of playlist tracks together with the total track count
     * reported by the Spotify API. Callers that need to paginate through all
     * tracks should use [SpotifyPlaylistTracksPage.total] to decide when to
     * stop instead of relying on page size, because [mapNotNull] filtering
     * (e.g. null track IDs) can shrink a page below [limit] even when more
     * pages remain.
     */
    fun getPlaylistTracksPage(accessToken: String, playlistId: String, offset: Int = 0, limit: Int = 100): SpotifyPlaylistTracksPage {
        val root = spotifyApiExecutor.execute(
            path = "playlists/$playlistId/tracks",
            token = accessToken,
            query = mapOf(
                "market" to "from_token",
                "offset" to offset.coerceAtLeast(0).toString(),
                "limit" to limit.coerceIn(1, 100).toString()
            )
        ) ?: return SpotifyPlaylistTracksPage(tracks = emptyList(), total = 0)

        val total = root["total"].jsonPrimitiveIntOrZero
        val items = root["items"] as? JsonArray ?: JsonArray(emptyList())
        val tracks = items.mapNotNull { parseTrack(it.jsonObject["track"].jsonObject) }
        return SpotifyPlaylistTracksPage(tracks = tracks, total = total)
    }

    fun searchTracks(accessToken: String, query: String, offset: Int = 0, limit: Int = 50): List<Track> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()

        val root = spotifyApiExecutor.execute(
            path = "search",
            token = accessToken,
            query = mapOf(
                "q" to normalizedQuery,
                "type" to "track",
                "market" to "from_token",
                "offset" to offset.coerceAtLeast(0).toString(),
                "limit" to limit.coerceIn(1, 50).toString()
            )
        ) ?: return emptyList()

        val items = root["tracks"].jsonObject["items"] as? JsonArray ?: JsonArray(emptyList())
        return items.mapNotNull { parseTrack(it.jsonObject) }
    }

    fun searchPlaylists(accessToken: String, query: String, offset: Int = 0, limit: Int = 50): List<Playlist> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()

        val root = spotifyApiExecutor.execute(
            path = "search",
            token = accessToken,
            query = mapOf(
                "q" to normalizedQuery,
                "type" to "playlist",
                "offset" to offset.coerceAtLeast(0).toString(),
                "limit" to limit.coerceIn(1, 50).toString()
            )
        ) ?: return emptyList()

        val items = root["playlists"].jsonObject["items"] as? JsonArray ?: JsonArray(emptyList())
        return items.mapNotNull { parsePlaylist(it.jsonObject) }
    }

    private fun parseTrack(trackObj: JsonObject): Track? {
        val id = trackObj["id"].jsonPrimitiveStringOrNull ?: return null
        val artists = trackObj["artists"] as? JsonArray
        val album = trackObj["album"].jsonObject
        return Track(
            id = id,
            title = trackObj["name"].jsonPrimitiveStringOrEmpty,
            artist = joinArtistNames(artists),
            album = album["name"].jsonPrimitiveStringOrNull,
            durationMs = trackObj["duration_ms"]?.jsonPrimitive?.longOrNull,
            source = SourceType.SPOTIFY,
            url = "spotify:track:$id",
            imageUrl = bestImageUrl(album["images"] as? JsonArray),
            enriched = true
        )
    }

    private fun parsePlaylist(obj: JsonObject): Playlist? {
        val id = obj["id"].jsonPrimitiveStringOrNull ?: return null
        return Playlist(
            id = id,
            name = obj["name"].jsonPrimitiveStringOrEmpty,
            owner = obj["owner"].jsonObject["display_name"].jsonPrimitiveStringOrNull ?: "Spotify",
            trackCount = obj["tracks"].jsonObject["total"].jsonPrimitiveIntOrZero,
            source = SourceType.SPOTIFY,
            imageUrl = bestImageUrl(obj["images"] as? JsonArray),
            description = obj["description"].jsonPrimitiveStringOrNull,
            tracks = null
        )
    }
}

data class SpotifyPlaylistTracksPage(
    val tracks: List<Track>,
    val total: Int
)
