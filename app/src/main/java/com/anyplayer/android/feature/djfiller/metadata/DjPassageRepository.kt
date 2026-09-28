package com.anyplayer.android.feature.djfiller.metadata

import android.content.Context
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.network.normalizeSyncServerAuthToken
import com.anyplayer.android.core.network.normalizeSyncServerBaseUrl
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Server-retrieved story passages for DJ breaks. Played receipts survive connection loss and
 *  restarts; played chunks are excluded locally for 30 days even before the server hears. */
@Singleton
class DjPassageRepository @Inject constructor(
    @ApplicationContext context: Context,
    httpClient: OkHttpClient,
    private val json: Json,
    private val syncPreferences: SyncPreferencesStore
) {
    private val prefs = context.getSharedPreferences("dj_passage_repository", Context.MODE_PRIVATE)
    private val client = httpClient.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()
    private val stateLock = Any()

    init {
        // The fact store's local state is obsolete; its endpoints are removed in sync-server v1.6.0.
        context.deleteSharedPreferences("dj_fact_repository")
    }

    @Serializable private data class Played(val chunkIds: List<Long>, val eventId: String, val playedAt: String)
    @Serializable private data class Usage(val chunkId: Long, val playedAt: String)
    @Serializable private data class State(val played: List<Played> = emptyList(), val usage: List<Usage> = emptyList())
    @Serializable private data class LookupResponse(val status: String, val passages: List<DjPassage> = emptyList())
    @Serializable private data class PlayedRequest(
        @SerialName("event_id") val eventId: String,
        @SerialName("played_at") val playedAt: String,
        @SerialName("chunk_ids") val chunkIds: List<Long>
    )
    private data class Account(val base: HttpUrl?, val token: String, val stateKey: String)

    suspend fun findPassages(track: Track): DjPassages? {
        val account = account()
        flushPending(account)
        val base = account.base ?: return null.also { CompatLog.w(TAG, "no sync server configured") }
        val response = withContext(Dispatchers.IO) {
            runCatching {
                val url = base.newBuilder().addPathSegments("v1/dj-passages")
                    .addQueryParameter("song", track.title)
                    .addQueryParameter("artist", track.artist)
                    .apply { track.album?.takeIf { it.isNotBlank() }?.let { addQueryParameter("album", it) } }
                    .build()
                client.executeDjServerRequest(authorized(account, url)).use { r ->
                    if (!r.isSuccessful) {
                        CompatLog.w(TAG, "passage lookup HTTP ${r.code}")
                        return@use null
                    }
                    r.body?.string()?.let { json.decodeFromString(LookupResponse.serializer(), it) }
                }
            }.onFailure {
                CompatLog.w(TAG, "passage lookup failed: ${if (it is java.io.IOException) it.message else it::class.simpleName}")
            }.getOrNull()
        } ?: return null
        if (response.status != "ok") {
            CompatLog.i(TAG, "passages status=${response.status}")
            return null
        }
        val excluded = recentlyUsed(account)
        val usable = fitToBudget(response.passages.filterNot { it.chunkId in excluded })
        if (usable.isEmpty()) {
            CompatLog.i(TAG, "passages status=ok but all ${response.passages.size} locally excluded")
            return null
        }
        CompatLog.i(TAG, "passages status=ok chunks=${usable.map { it.chunkId }} sources=${usable.map { it.source }.distinct()}")
        return DjPassages(usable)
    }

    /** Commit on the playback thread before any network work; one UUID survives every retry. */
    fun recordPlayed(passages: DjPassages) {
        val account = account()
        val now = Instant.now().toString()
        val cutoff = Instant.now().minusSeconds(EXCLUSION_SECONDS)
        synchronized(stateLock) {
            val state = read(account)
            write(account, state.copy(
                played = state.played + passages.chunkIds.chunked(MAX_PLAYED_CHUNKS)
                    .map { Played(it, UUID.randomUUID().toString(), now) },
                usage = state.usage.filter { isAfter(it.playedAt, cutoff) } + passages.chunkIds.map { Usage(it, now) }
            ))
        }
        scope.launch { flushPending() }
    }

    internal suspend fun flushPending() = flushPending(account())

    private suspend fun flushPending(account: Account) = flushMutex.withLock {
        withContext(Dispatchers.IO) {
            val base = account.base ?: return@withContext
            val url = base.newBuilder().addPathSegments("v1/dj-passages/played").build()
            for (event in synchronized(stateLock) { read(account).played }) {
                val payload = json.encodeToString(PlayedRequest.serializer(), PlayedRequest(event.eventId, event.playedAt, event.chunkIds))
                val code = runCatching {
                    client.executeDjServerRequest(authorized(account, url, payload)).use { it.code }
                }.getOrNull()
                when {
                    code == 204 -> Unit
                    code != null && code in 400..499 && code != 408 && code != 429 ->
                        CompatLog.w(TAG, "played event rejected (HTTP $code); dropping")
                    else -> return@withContext // offline, 5xx, 408, 429 or IO error: keep and retry later
                }
                synchronized(stateLock) {
                    val state = read(account)
                    write(account, state.copy(played = state.played.filterNot { it.eventId == event.eventId }))
                }
            }
        }
    }

    private fun recentlyUsed(account: Account): Set<Long> {
        val cutoff = Instant.now().minusSeconds(EXCLUSION_SECONDS)
        return synchronized(stateLock) { read(account).usage.filter { isAfter(it.playedAt, cutoff) }.map { it.chunkId }.toSet() }
    }

    private fun isAfter(timestamp: String, cutoff: Instant) =
        runCatching { Instant.parse(timestamp).isAfter(cutoff) }.getOrDefault(false)

    private fun read(account: Account): State = prefs.getString(account.stateKey, null)?.let {
        runCatching { json.decodeFromString(State.serializer(), it) }.getOrNull()
    } ?: State()

    private fun write(account: Account, state: State) {
        check(prefs.edit().putString(account.stateKey, json.encodeToString(State.serializer(), state)).commit()) {
            "Could not persist DJ passage playback state"
        }
    }

    private fun account(): Account {
        val settings = syncPreferences.read()
        val base = normalizeSyncServerBaseUrl(settings.serverTarget)
        val token = normalizeSyncServerAuthToken(settings.authToken)
        val identity = MessageDigest.getInstance("SHA-256")
            .digest("$base\u0000$token".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return Account(base.toHttpUrlOrNull(), token, "state_$identity")
    }

    private fun authorized(account: Account, url: HttpUrl, payload: String? = null): Request =
        Request.Builder().url(url).apply {
            if (account.token.isNotEmpty()) header("Authorization", "Bearer ${account.token}")
            payload?.let { post(it.toRequestBody("application/json".toMediaType())) }
        }.build()

    private companion object {
        const val TAG = "DjPassageRepository"
        const val EXCLUSION_SECONDS = 30L * 24 * 60 * 60
        const val MAX_PLAYED_CHUNKS = 16
    }
}
