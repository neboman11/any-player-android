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
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownServiceException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JellyfinClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    fun validate(url: String, apiKey: String): ProviderConnectionCheck {
        return runCatching {
            val normalized = url.trimEnd('/')
            val headers = authHeaders(apiKey)

            val infoRequest = Request.Builder()
                .url("$normalized/System/Info")
                .headers(headers)
                .build()

            okHttpClient.newCall(infoRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return ProviderConnectionCheck.Failed("Jellyfin auth failed: HTTP ${response.code}")
                }
            }

            val usersRequest = Request.Builder()
                .url("$normalized/Users")
                .headers(headers)
                .build()

            okHttpClient.newCall(usersRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return ProviderConnectionCheck.Failed("Jellyfin users request failed: HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val users = parseJsonArray(body)
                val firstUser = users.firstOrNull()?.jsonObject
                val username = firstUser?.get("Name")?.jsonPrimitiveStringOrNull
                val userId = firstUser?.get("Id")?.jsonPrimitiveStringOrNull
                if (userId.isNullOrBlank()) {
                    return ProviderConnectionCheck.Failed("Jellyfin connection did not return a user ID")
                }
                ProviderConnectionCheck.Connected(
                    username = username,
                    metadata = mapOf("userId" to userId)
                )
            }
        }.getOrElse { error ->
            ProviderConnectionCheck.Failed(mapConnectionError(error))
        }
    }

    private fun mapConnectionError(error: Throwable): String {
        val root = rootCause(error)
        val message = root.message?.trim().orEmpty()
        val lowerMessage = message.lowercase()
        return when (root) {
            is UnknownHostException -> "Unable to resolve Jellyfin server host. Check the URL and network."
            is ConnectException -> "Unable to reach Jellyfin server. Verify host/port and that the server is running."
            is SocketTimeoutException -> "Jellyfin server timed out. Check network connectivity and try again."
            is SSLException -> "TLS/SSL handshake failed. Verify HTTPS certificate settings or use the correct protocol."
            is UnknownServiceException -> {
                if (lowerMessage.contains("cleartext") && lowerMessage.contains("not permitted")) {
                    "Cleartext HTTP is blocked by Android network security policy for this host. Use HTTPS, localhost/emulator loopback, or allow cleartext traffic in app config."
                } else {
                    "Jellyfin service configuration is not supported by the current network stack."
                }
            }
            is IllegalArgumentException -> "Jellyfin URL is invalid. Include host and optional port (for example, http://192.168.1.10:8096)."
            is IOException -> {
                if (lowerMessage.contains("cleartext") && lowerMessage.contains("not permitted")) {
                    "Cleartext HTTP is blocked by Android network security policy for this host. Use HTTPS, localhost/emulator loopback, or allow cleartext traffic in app config."
                } else if (message.isNotBlank()) {
                    "Network error while connecting to Jellyfin: $message"
                } else {
                    "Network error while connecting to Jellyfin."
                }
            }
            else -> if (message.isNotBlank()) {
                "Unexpected Jellyfin error (${root::class.simpleName}): $message"
            } else {
                "Unexpected Jellyfin error (${root::class.simpleName})."
            }
        }
    }

    private tailrec fun rootCause(throwable: Throwable): Throwable {
        val cause = throwable.cause ?: return throwable
        return rootCause(cause)
    }

    fun getPlaylists(url: String, apiKey: String, userId: String?, offset: Int = 0, limit: Int = 100): List<Playlist> {
        if (userId.isNullOrBlank()) return emptyList()
        val normalized = url.trimEnd('/')
        val endpoint = buildUrl(
            baseUrl = normalized,
            path = "Users/$userId/Items",
            query = mapOf(
                "Recursive" to "true",
                "IncludeItemTypes" to "Playlist",
                "StartIndex" to offset.toString(),
                "Limit" to limit.coerceAtLeast(1).toString()
            )
        )
        val root = execute(endpoint, authHeaders(apiKey)) ?: return emptyList()
        val items = root["Items"] as? JsonArray ?: JsonArray(emptyList())
        return items.map { it.jsonObject }.map { item ->
            Playlist(
                id = item["Id"].jsonPrimitiveStringOrEmpty,
                name = item["Name"].jsonPrimitiveStringOrEmpty,
                owner = "Jellyfin",
                trackCount = item["ChildCount"].jsonPrimitiveIntOrZero,
                source = SourceType.JELLYFIN,
                imageUrl = null,
                description = null,
                tracks = null
            )
        }
    }

    fun getPlaylistTracks(
        url: String,
        apiKey: String,
        playlistId: String,
        userId: String?,
        offset: Int = 0,
        limit: Int = 300
    ): List<Track> {
        val normalized = url.trimEnd('/')
        val playlistQuery = buildMap {
            put("StartIndex", offset.toString())
            put("Limit", limit.coerceAtLeast(1).toString())
            put("Recursive", "true")
            put("IncludeItemTypes", "Audio")
            if (!userId.isNullOrBlank()) {
                put("UserId", userId)
            }
        }
        val endpoint = buildUrl(
            baseUrl = normalized,
            path = "Playlists/$playlistId/Items",
            query = playlistQuery
        )
        val root = execute(endpoint, authHeaders(apiKey))
        val tracksFromPlaylistEndpoint = parseTrackItems(root, normalized, userId, apiKey)
        if (tracksFromPlaylistEndpoint.isNotEmpty() || userId.isNullOrBlank()) {
            return tracksFromPlaylistEndpoint
        }

        val fallbackEndpoint = buildUrl(
            baseUrl = normalized,
            path = "Users/$userId/Items",
            query = mapOf(
                "ParentId" to playlistId,
                "Recursive" to "true",
                "IncludeItemTypes" to "Audio",
                "StartIndex" to offset.toString(),
                "Limit" to limit.coerceAtLeast(1).toString()
            )
        )
        return parseTrackItems(execute(fallbackEndpoint, authHeaders(apiKey)), normalized, userId, apiKey)
    }

    private fun parseTrackItems(root: JsonObject?, normalizedUrl: String, userId: String?, apiKey: String): List<Track> {
        val items = root?.get("Items") as? JsonArray ?: JsonArray(emptyList())
        return items.mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["Id"].jsonPrimitiveStringOrNull ?: return@mapNotNull null
            val title = obj["Name"].jsonPrimitiveStringOrNull ?: "Unknown"
            val artists = obj["Artists"] as? JsonArray
            val artist = artists?.firstOrNull()?.jsonPrimitiveStringOrNull ?: "Unknown Artist"
            val runtimeTicks = obj["RunTimeTicks"]?.jsonPrimitive?.longOrNull
            val streamUrl = if (!userId.isNullOrBlank()) {
                buildStreamUrl(
                    normalizedUrl = normalizedUrl,
                    audioId = id,
                    userId = userId,
                    apiKey = apiKey
                )
            } else {
                null
            }
            Track(
                id = id,
                title = title,
                artist = artist,
                album = obj["Album"].jsonPrimitiveStringOrNull,
                durationMs = runtimeTicks?.div(10_000),
                source = SourceType.JELLYFIN,
                url = streamUrl,
                imageUrl = null,
                bitrateKbps = null,
                sampleRateHz = null,
                enriched = true
            )
        }
    }

    fun searchTracks(
        url: String,
        apiKey: String,
        userId: String?,
        query: String,
        offset: Int = 0,
        limit: Int = 100
    ): List<Track> {
        if (userId.isNullOrBlank()) return emptyList()
        val normalized = url.trimEnd('/')
        val endpoint = buildUrl(
            baseUrl = normalized,
            path = "Users/$userId/Items",
            query = mapOf(
                "Recursive" to "true",
                "IncludeItemTypes" to "Audio",
                "SearchTerm" to query,
                "StartIndex" to offset.toString(),
                "Limit" to limit.coerceAtLeast(1).toString()
            )
        )
        val root = execute(endpoint, authHeaders(apiKey)) ?: return emptyList()
        val items = root["Items"] as? JsonArray ?: JsonArray(emptyList())
        return items.mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["Id"].jsonPrimitiveStringOrNull ?: return@mapNotNull null
            Track(
                id = id,
                title = obj["Name"].jsonPrimitiveStringOrEmpty,
                artist = (obj["Artists"] as? JsonArray)?.firstOrNull()?.jsonPrimitiveStringOrNull ?: "Unknown Artist",
                album = obj["Album"].jsonPrimitiveStringOrNull,
                durationMs = obj["RunTimeTicks"]?.jsonPrimitive?.longOrNull?.div(10_000),
                source = SourceType.JELLYFIN,
                url = buildStreamUrl(
                    normalizedUrl = normalized,
                    audioId = id,
                    userId = userId,
                    apiKey = apiKey
                ),
                imageUrl = null,
                enriched = true
            )
        }
    }

    private fun buildStreamUrl(
        normalizedUrl: String,
        audioId: String,
        userId: String,
        apiKey: String
    ): String {
        val base = "$normalizedUrl/Audio/$audioId/universal".toHttpUrlOrNull()
            ?: return "$normalizedUrl/Audio/$audioId/universal?UserId=$userId&Container=opus,mp3,aac,m4a,flac,webma,webm,wav,ogg&AudioCodec=aac,mp3,vorbis,opus&api_key=$apiKey"
        return base.newBuilder()
            .addQueryParameter("UserId", userId)
            .addQueryParameter("Container", "opus,mp3,aac,m4a,flac,webma,webm,wav,ogg")
            .addQueryParameter("AudioCodec", "aac,mp3,vorbis,opus")
            .addQueryParameter("api_key", apiKey)
            .build()
            .toString()
    }

    fun searchPlaylists(
        url: String,
        apiKey: String,
        userId: String?,
        query: String,
        offset: Int = 0,
        limit: Int = 100
    ): List<Playlist> {
        if (userId.isNullOrBlank()) return emptyList()
        val normalized = url.trimEnd('/')
        val endpoint = buildUrl(
            baseUrl = normalized,
            path = "Users/$userId/Items",
            query = mapOf(
                "Recursive" to "true",
                "IncludeItemTypes" to "Playlist",
                "SearchTerm" to query,
                "StartIndex" to offset.toString(),
                "Limit" to limit.coerceAtLeast(1).toString()
            )
        )
        val root = execute(endpoint, authHeaders(apiKey)) ?: return emptyList()
        val items = root["Items"] as? JsonArray ?: JsonArray(emptyList())
        return items.map { it.jsonObject }.map { item ->
            Playlist(
                id = item["Id"].jsonPrimitiveStringOrEmpty,
                name = item["Name"].jsonPrimitiveStringOrEmpty,
                owner = "Jellyfin",
                trackCount = item["ChildCount"].jsonPrimitiveIntOrZero,
                source = SourceType.JELLYFIN,
                imageUrl = null,
                description = null,
                tracks = null
            )
        }
    }

    private fun execute(url: String, headers: okhttp3.Headers): JsonObject? {
        val request = Request.Builder().url(url).headers(headers).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            val parsed = json.parseToJsonElement(body)
            return parsed as? JsonObject
        }
    }

    private fun parseJsonArray(raw: String): JsonArray {
        val parsed = json.parseToJsonElement(raw)
        return parsed as? JsonArray ?: JsonArray(emptyList())
    }

    private fun buildUrl(baseUrl: String, path: String, query: Map<String, String>): String {
        val base = "$baseUrl/${path.trimStart('/')}".toHttpUrlOrNull() ?: return "$baseUrl/${path.trimStart('/')}"
        val builder = base.newBuilder()
        query.forEach { (key, value) ->
            builder.addQueryParameter(key, value)
        }
        return builder.build().toString()
    }

    private fun authHeaders(apiKey: String): okhttp3.Headers = okhttp3.Headers.Builder()
        .add("X-Emby-Token", apiKey)
        .add(
            "X-Emby-Authorization",
            "MediaBrowser Token=\"$apiKey\", Client=\"AnyPlayer\", Device=\"AnyPlayer\", DeviceId=\"AnyPlayer\", Version=\"1.0.0\""
        )
        .build()
}

private val JsonElement?.jsonObject: JsonObject
    get() = this as? JsonObject ?: JsonObject(emptyMap())

private val JsonElement?.jsonPrimitiveStringOrNull: String?
    get() = this?.jsonPrimitive?.contentOrNull

private val JsonElement?.jsonPrimitiveStringOrEmpty: String
    get() = this?.jsonPrimitive?.contentOrNull.orEmpty()

private val JsonElement?.jsonPrimitiveIntOrZero: Int
    get() = this?.jsonPrimitive?.intOrNull ?: 0
