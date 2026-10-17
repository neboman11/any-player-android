package com.anyplayer.android.data.providers

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.StoredConnection
import com.anyplayer.android.data.net.HttpFailure
import com.anyplayer.android.data.net.executeRetrying
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

data class ValidationResult(val username: String? = null, val premium: Boolean? = null, val jellyfinUserId: String? = null)
interface ProviderClient {
    val source: SourceType
    suspend fun validate(conn: StoredConnection): ValidationResult
    suspend fun playlists(conn: StoredConnection): List<Playlist>
    suspend fun playlistTracks(conn: StoredConnection, playlistId: String): List<Track>
    suspend fun search(conn: StoredConnection, query: String, type: SearchType, limit: Int): SearchResult
}

@Singleton
class ProviderRegistry @Inject constructor(jellyfin: JellyfinClient, plex: PlexClient, spotify: SpotifyClient) {
    private val clients = listOf(jellyfin, plex, spotify).associateBy { it.source }
    operator fun get(source: SourceType): ProviderClient? = clients[source]
}

internal suspend fun OkHttpClient.json(request: Request): JsonObject = withContext(Dispatchers.IO) {
    executeRetrying(request).use { response ->
        if (!response.isSuccessful) throw HttpFailure(response.code, "HTTP ${response.code}")
        AppJson.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
    }
}
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.array(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
