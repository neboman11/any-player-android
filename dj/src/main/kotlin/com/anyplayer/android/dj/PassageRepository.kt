package com.anyplayer.android.dj

import android.content.Context
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.net.await
import com.anyplayer.android.data.settings.SyncSettingsStore
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class Passage(
    @SerialName("chunk_id") val chunkId: Long,
    val text: String,
    val source: String,
    @SerialName("source_url") val sourceUrl: String = "",
    val title: String = "", val subject: String = "", val lang: String = "",
)
@Serializable
data class Passages(val passages: List<Passage>) {
    val chunkIds: List<Long> get() = passages.map { it.chunkId }
}
internal fun estimateTokens(text: String): Int {
    val nonLatin = text.count { it.code > 0x2FF && !it.isWhitespace() }
    val latin = text.count { it.code <= 0x2FF && !it.isWhitespace() }
    return (latin + 2) / 3 + nonLatin
}
internal fun fitToBudget(passages: List<Passage>, budget: Int = 2500): List<Passage> {
    val kept = mutableListOf<Passage>(); var used = 0
    for (passage in passages) {
        val cost = estimateTokens(passage.text)
        if (kept.isNotEmpty() && used + cost > budget) break
        kept += passage; used += cost
    }
    return kept
}

/** Storage boundary permits JVM tests without loading Android or crypto on the caller. */
internal interface PassageStateStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}
internal class PreferencePassageStateStore(private val context: Context) : PassageStateStore {
    private val prefs by lazy { context.getSharedPreferences("dj_passage_repository", Context.MODE_PRIVATE) }
    override fun read(key: String) = prefs.getString(key, null)
    override fun write(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}

class PassageRepository internal constructor(
    http: OkHttpClient,
    private val settings: SyncSettingsStore,
    private val store: PassageStateStore,
    private val scope: CoroutineScope,
    private val diagnostics: DjDiagnostics,
    private val now: () -> Instant = Instant::now,
) {
    private val json = Json { ignoreUnknownKeys = true }
    // Retry policy belongs to this repository: OkHttp otherwise retries HTTP 408 itself.
    private val client = http.newBuilder().retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS).build()
    private val stateMutex = Mutex()
    private val flushMutex = Mutex()
    @Serializable private data class Played(val chunkIds: List<Long>, val eventId: String, val playedAt: String)
    @Serializable private data class Usage(val chunkId: Long, val playedAt: String)
    @Serializable private data class State(val played: List<Played> = emptyList(), val usage: List<Usage> = emptyList())
    @Serializable private data class Lookup(val status: String, val passages: List<Passage> = emptyList())
    @Serializable private data class Receipt(@SerialName("event_id") val eventId: String,
        @SerialName("played_at") val playedAt: String, @SerialName("chunk_ids") val chunkIds: List<Long>)

    suspend fun findPassages(track: Track): Passages? = withContext(Dispatchers.IO) {
        val account = settings.djAccount()
        if (account.base.isBlank()) return@withContext null
        // Receipts never delay the lookup, even when the server is offline.
        scope.launch { flushPending(account) }
        val target = (account.base + "/v1/dj-passages").toHttpUrl().newBuilder()
            .addQueryParameter("song", track.title).addQueryParameter("artist", track.artist)
            .apply { track.album?.takeIf(String::isNotBlank)?.let { addQueryParameter("album", it) } }.build()
        val request = account.request("v1/dj-passages").url(target).build()
        try {
            var lookup: Lookup? = null
            for (attempt in 0..2) {
                var retry = false
                client.newCall(request).await().use { response ->
                    retry = response.code == 503 && attempt < 2
                    if (response.code == 200) lookup = response.body?.string()?.let { json.decodeFromString<Lookup>(it) }
                }
                if (!retry) break
                delay(250L shl attempt)
            }
            if (lookup?.status != "ok") return@withContext null
            val excluded = stateMutex.withLock { read(account).usage.filter { recent(it.playedAt) }.map { it.chunkId }.toSet() }
            fitToBudget(lookup!!.passages.filterNot { it.chunkId in excluded }).takeIf { it.isNotEmpty() }?.let(::Passages)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            diagnostics.log("DjPassages", "Passage lookup failed", e); null
        }
    }

    suspend fun recordPlayed(passages: Passages) = withContext(Dispatchers.IO) {
        val account = settings.djAccount(); val timestamp = now().toString()
        stateMutex.withLock {
            val state = read(account)
            val pending = state.played + passages.chunkIds.chunked(16).map { Played(it, UUID.randomUUID().toString(), timestamp) }
            if (pending.size > MAX_PENDING) diagnostics.log("DjPassages", "Dropping oldest pending receipts at queue limit")
            write(account, State(pending.takeLast(MAX_PENDING),
                state.usage.filter { recent(it.playedAt) } + passages.chunkIds.map { Usage(it, timestamp) }))
        }
        scope.launch { flushPending(account) }
    }
    internal suspend fun flushPending() = flushPending(settings.djAccount())
    private suspend fun flushPending(account: DjAccount) = flushMutex.withLock {
        withContext(Dispatchers.IO) {
            if (account.base.isBlank()) return@withContext
            val pending = stateMutex.withLock { read(account).played }
            for (event in pending) {
                val body = json.encodeToString(Receipt(event.eventId, event.playedAt, event.chunkIds))
                val code = try {
                    client.newCall(account.request("v1/dj-passages/played")
                        .post(body.toRequestBody("application/json".toMediaType())).build()).await().use { it.code }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return@withContext
                }
                when {
                    code == 204 -> Unit
                    code in 400..499 && code != 408 && code != 429 -> diagnostics.log("DjPassages", "Played receipt rejected (HTTP $code); dropping")
                    else -> return@withContext
                }
                stateMutex.withLock { val state = read(account); write(account, state.copy(played = state.played.filterNot { it.eventId == event.eventId })) }
            }
        }
    }
    private fun recent(timestamp: String) = runCatching { Instant.parse(timestamp).isAfter(now().minusSeconds(EXCLUSION_SECONDS)) }.getOrDefault(false)
    private fun read(account: DjAccount): State = store.read(account.stateKey)?.let {
        runCatching { json.decodeFromString<State>(it) }.getOrNull()
    } ?: State()
    private fun write(account: DjAccount, state: State) = store.write(account.stateKey, json.encodeToString(state))
    internal companion object {
        const val EXCLUSION_SECONDS = 30L * 24 * 60 * 60
        const val MAX_PENDING = 512
    }
}
