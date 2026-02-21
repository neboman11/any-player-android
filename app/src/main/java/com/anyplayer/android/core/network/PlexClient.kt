package com.anyplayer.android.core.network

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlexClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    fun validate(url: String, token: String): ProviderConnectionCheck {
        return runCatching {
            val normalized = url.trimEnd('/')
            val request = Request.Builder().url("$normalized/identity?X-Plex-Token=$token").build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    ProviderConnectionCheck.Connected(username = "Plex User")
                } else {
                    ProviderConnectionCheck.Failed("Plex auth failed: HTTP ${response.code}")
                }
            }
        }.getOrElse { error ->
            ProviderConnectionCheck.Failed(error.message ?: "Plex connection failed")
        }
    }

    fun getPlaylists(url: String, token: String, offset: Int = 0, limit: Int = 100): List<Playlist> {
        val root = execute(
            buildUrl(
                baseUrl = url.trimEnd('/'),
                path = "playlists/all",
                query = mapOf(
                    "type" to "15",
                    "X-Plex-Token" to token,
                    "X-Plex-Container-Start" to offset.toString(),
                    "X-Plex-Container-Size" to limit.coerceAtLeast(1).toString()
                )
            )
        ) ?: return emptyList()
        val metadata = root["MediaContainer"].jsonObject["Metadata"] as? JsonArray ?: JsonArray(emptyList())
        return metadata.map { it.jsonObject }.mapNotNull { item ->
            val id = item["ratingKey"].jsonPrimitiveStringOrNull ?: return@mapNotNull null
            Playlist(
                id = id,
                name = item["title"].jsonPrimitiveStringOrEmpty,
                owner = "Plex",
                trackCount = item["leafCount"].jsonPrimitiveIntOrZero,
                source = SourceType.PLEX,
                imageUrl = null,
                description = item["summary"].jsonPrimitiveStringOrNull,
                tracks = null
            )
        }
    }

    fun getPlaylistTracks(url: String, token: String, playlistId: String, offset: Int = 0, limit: Int = 300): List<Track> {
        val normalized = url.trimEnd('/')
        val root = execute(
            buildUrl(
                baseUrl = normalized,
                path = "playlists/$playlistId/items",
                query = mapOf(
                    "X-Plex-Token" to token,
                    "X-Plex-Container-Start" to offset.toString(),
                    "X-Plex-Container-Size" to limit.coerceAtLeast(1).toString()
                )
            )
        ) ?: return emptyList()
        val metadata = root["MediaContainer"].jsonObject["Metadata"] as? JsonArray ?: JsonArray(emptyList())
        return metadata.mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["ratingKey"].jsonPrimitiveStringOrNull ?: return@mapNotNull null
            val media = obj["Media"] as? JsonArray
            val partUrl = media?.firstOrNull()?.jsonObject?.get("Part")
                ?.let { it as? JsonArray }
                ?.firstOrNull()
                ?.jsonObject
                ?.get("key")
                ?.jsonPrimitiveStringOrNull
                ?.let { buildAuthenticatedUrl(normalized, it, token) }

            val fallbackTrackKeyUrl = obj["key"]
                .jsonPrimitiveStringOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { buildAuthenticatedUrl(normalized, it, token) }

            val fallbackDownloadUrl = buildAuthenticatedUrl(
                baseUrl = normalized,
                resourcePath = "library/metadata/$id",
                token = token,
                extraQuery = mapOf("download" to "1")
            )

            val streamUrl = partUrl ?: fallbackTrackKeyUrl ?: fallbackDownloadUrl

            Track(
                id = id,
                title = obj["title"].jsonPrimitiveStringOrEmpty,
                artist = obj["grandparentTitle"].jsonPrimitiveStringOrNull ?: "Unknown Artist",
                album = obj["parentTitle"].jsonPrimitiveStringOrNull,
                durationMs = obj["duration"]?.jsonPrimitive?.longOrNull,
                source = SourceType.PLEX,
                url = streamUrl,
                imageUrl = buildArtworkUrl(normalized, obj, token),
                enriched = true
            )
        }
    }

    private fun buildAuthenticatedUrl(
        baseUrl: String,
        resourcePath: String,
        token: String,
        extraQuery: Map<String, String> = emptyMap()
    ): String {
        val normalizedBase = baseUrl.trimEnd('/')
        val combined = "$normalizedBase/${resourcePath.trimStart('/')}"
        val httpUrl = combined.toHttpUrlOrNull()
        if (httpUrl == null) {
            val separator = if (combined.contains("?")) "&" else "?"
            val extra = if (extraQuery.isEmpty()) "" else extraQuery.entries.joinToString("&", prefix = "&") { (key, value) -> "$key=$value" }
            return "$combined${separator}X-Plex-Token=$token$extra"
        }
        val builder = httpUrl.newBuilder()
        extraQuery.forEach { (key, value) ->
            builder.addQueryParameter(key, value)
        }
        builder.addQueryParameter("X-Plex-Token", token)
        return builder.build().toString()
    }

    fun searchTracks(url: String, token: String, query: String, offset: Int = 0, limit: Int = 100): List<Track> {
        val normalized = url.trimEnd('/')
        val root = execute(
            buildUrl(
                baseUrl = normalized,
                path = "search",
                query = mapOf(
                    "query" to query,
                    "type" to "10",
                    "X-Plex-Token" to token,
                    "X-Plex-Container-Start" to offset.toString(),
                    "X-Plex-Container-Size" to limit.coerceAtLeast(1).toString()
                )
            )
        ) ?: return emptyList()
        val metadata = root["MediaContainer"].jsonObject["Metadata"] as? JsonArray ?: JsonArray(emptyList())
        return metadata.mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["ratingKey"].jsonPrimitiveStringOrNull ?: return@mapNotNull null
            Track(
                id = id,
                title = obj["title"].jsonPrimitiveStringOrEmpty,
                artist = obj["grandparentTitle"].jsonPrimitiveStringOrNull ?: "Unknown Artist",
                album = obj["parentTitle"].jsonPrimitiveStringOrNull,
                durationMs = obj["duration"]?.jsonPrimitive?.longOrNull,
                source = SourceType.PLEX,
                url = null,
                imageUrl = buildArtworkUrl(normalized, obj, token),
                enriched = false
            )
        }
    }

    private fun buildArtworkUrl(baseUrl: String, trackObject: JsonObject, token: String): String? {
        val thumbPath = trackObject["thumb"].jsonPrimitiveStringOrNull
            ?: trackObject["parentThumb"].jsonPrimitiveStringOrNull
            ?: trackObject["grandparentThumb"].jsonPrimitiveStringOrNull

        if (!thumbPath.isNullOrBlank()) {
            return buildAuthenticatedUrl(baseUrl, thumbPath, token)
        }

        val ratingKey = trackObject["ratingKey"].jsonPrimitiveStringOrNull ?: return null
        return buildAuthenticatedUrl(baseUrl, "library/metadata/$ratingKey/thumb", token)
    }

    fun searchPlaylists(url: String, token: String, query: String, offset: Int = 0, limit: Int = 100): List<Playlist> {
        val normalized = url.trimEnd('/')
        val root = execute(
            buildUrl(
                baseUrl = normalized,
                path = "search",
                query = mapOf(
                    "query" to query,
                    "type" to "15",
                    "X-Plex-Token" to token,
                    "X-Plex-Container-Start" to offset.toString(),
                    "X-Plex-Container-Size" to limit.coerceAtLeast(1).toString()
                )
            )
        ) ?: return emptyList()
        val metadata = root["MediaContainer"].jsonObject["Metadata"] as? JsonArray ?: JsonArray(emptyList())
        return metadata.map { it.jsonObject }.mapNotNull { item ->
            val id = item["ratingKey"].jsonPrimitiveStringOrNull ?: return@mapNotNull null
            Playlist(
                id = id,
                name = item["title"].jsonPrimitiveStringOrEmpty,
                owner = "Plex",
                trackCount = item["leafCount"].jsonPrimitiveIntOrZero,
                source = SourceType.PLEX,
                imageUrl = null,
                description = item["summary"].jsonPrimitiveStringOrNull,
                tracks = null
            )
        }
    }

    private fun execute(url: String): JsonObject? {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            val parsed = json.parseToJsonElement(body)
            return parsed as? JsonObject
        }
    }

    private fun buildUrl(baseUrl: String, path: String, query: Map<String, String>): String {
        val base = "$baseUrl/${path.trimStart('/')}".toHttpUrlOrNull() ?: return "$baseUrl/${path.trimStart('/')}"
        val builder = base.newBuilder()
        query.forEach { (key, value) ->
            builder.addQueryParameter(key, value)
        }
        return builder.build().toString()
    }
}

private val JsonElement?.jsonObject: JsonObject
    get() = this as? JsonObject ?: JsonObject(emptyMap())

private val JsonElement?.jsonPrimitiveStringOrNull: String?
    get() = this?.jsonPrimitive?.contentOrNull

private val JsonElement?.jsonPrimitiveStringOrEmpty: String
    get() = this?.jsonPrimitive?.contentOrNull.orEmpty()

private val JsonElement?.jsonPrimitiveIntOrZero: Int
    get() = this?.jsonPrimitive?.intOrNull ?: 0
