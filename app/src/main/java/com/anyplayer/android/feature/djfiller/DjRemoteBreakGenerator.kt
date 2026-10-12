package com.anyplayer.android.feature.djfiller

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.network.normalizeSyncServerAuthToken
import com.anyplayer.android.core.network.normalizeSyncServerBaseUrl
import com.anyplayer.android.feature.djfiller.metadata.DjPassages
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Has the sync server write and voice a DJ break (`POST /v1/dj-breaks`, see
 *  any-player-sync-server `dj_generate.rs`) instead of the on-device script model and voice.
 *  Remote is the default; [setEnabled] switches to on-device generation. */
@Singleton
class DjRemoteBreakGenerator @Inject constructor(
    httpClient: OkHttpClient,
    private val json: Json,
    private val syncPreferencesStore: SyncPreferencesStore,
    private val djVoiceSynthesizer: DjVoiceSynthesizer
) {
    private companion object {
        const val TAG = "DjRemoteBreakGenerator"
        const val NOT_CONFIGURED = "This sync server can't generate AI DJ breaks. Update it, or switch AI DJ to on-device generation."
    }

    // A draft, a fact check and speech on a CPU-only server can take a few minutes.
    private val client = httpClient.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(6, TimeUnit.MINUTES)
        .build()

    private val mutableEnabled = MutableStateFlow(syncPreferencesStore.djRemoteGeneration())
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    fun isEnabled(): Boolean = mutableEnabled.value

    fun setEnabled(remote: Boolean) {
        syncPreferencesStore.setDjRemoteGeneration(remote)
        mutableEnabled.value = remote
    }

    @Serializable
    private data class PassageBody(@SerialName("chunk_id") val chunkId: Long, val text: String, val source: String)

    @Serializable
    private data class BreakBody(
        val title: String,
        val artist: String,
        val passages: List<PassageBody>,
        @SerialName("voice_id") val voiceId: String?
    )

    /** Writes the break's WAV to [outputFile]; false (logged) when the server is unreachable,
     *  unconfigured, or its fact check rejected the script. */
    suspend fun generate(track: Track, passages: DjPassages, outputFile: File): Boolean {
        val prefs = syncPreferencesStore.read()
        val base = normalizeSyncServerBaseUrl(prefs.serverTarget)
        if (base.isBlank()) {
            CompatLog.w(TAG, "AI DJ: sync server is not configured")
            return false
        }
        val token = normalizeSyncServerAuthToken(prefs.authToken)
        val body = json.encodeToString(
            BreakBody.serializer(),
            BreakBody(
                title = track.title,
                artist = track.artist,
                passages = passages.passages.map { PassageBody(it.chunkId, it.text, it.source) },
                voiceId = syncPreferencesStore.selectedDjVoiceId()
            )
        )
        val request = Request.Builder()
            .url("$base/v1/dj-breaks")
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            CompatLog.w(TAG, "AI DJ: ${describeFailedSyncResponse(null, e, NOT_CONFIGURED)}")
            return false
        }
        response.use {
            if (it.code == 204) {
                CompatLog.w(TAG, "AI DJ: sync server's fact check rejected the script for trackId=${track.id}")
                return false
            }
            if (!it.isSuccessful) {
                CompatLog.w(TAG, "AI DJ: ${describeFailedSyncResponse(it, null, NOT_CONFIGURED)}")
                return false
            }
            val written = runCatching {
                withContext(Dispatchers.IO) {
                    outputFile.outputStream().use { out -> it.body!!.byteStream().copyTo(out) }
                }
            }
            written.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
                CompatLog.w(TAG, "AI DJ: server audio download was interrupted", error)
                return false
            }
        }
        return djVoiceSynthesizer.normalizeWaveFile(outputFile)
    }
}

/** Enqueued rather than executed so cancelling the generation also cancels the HTTP call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
