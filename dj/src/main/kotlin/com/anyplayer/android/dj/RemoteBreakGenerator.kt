package com.anyplayer.android.dj

import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.net.await
import com.anyplayer.android.data.settings.DjSettingsStore
import com.anyplayer.android.data.settings.SyncSettingsStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class RemoteBreakGenerator internal constructor(
    http: OkHttpClient, private val sync: SyncSettingsStore, private val settings: DjSettingsStore,
    private val voices: VoiceSynthesizer, private val diagnostics: DjDiagnostics,
) {
    private val client = http.newBuilder().readTimeout(5, TimeUnit.MINUTES).callTimeout(6, TimeUnit.MINUTES).build()
    @Serializable private data class BodyPassage(@SerialName("chunk_id") val chunkId: Long, val text: String, val source: String)
    @Serializable private data class Body(val title: String, val artist: String, val passages: List<BodyPassage>,
        @SerialName("voice_id") val voiceId: String? = null)
    suspend fun generate(track: Track, passages: Passages, file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val account = sync.djAccount()
            if (account.base.isBlank()) return@withContext false
            val body = Json.encodeToString(Body(track.title, track.artist,
                passages.passages.map { BodyPassage(it.chunkId, it.text, it.source) }, settings.awaitLoaded().selectedVoiceId))
            client.newCall(account.request("v1/dj-breaks").post(body.toRequestBody("application/json".toMediaType())).build())
                .await().use { response ->
                    if (response.code == 204) { diagnostics.log("DjRemote", "Server fact-check rejected script"); return@withContext false }
                    if (response.code != 200) {
                        diagnostics.log("DjRemote", describeFailure(response.code, null,
                            "This sync server doesn't have AI DJ break generation configured yet. Ask the server admin."))
                        return@withContext false
                    }
                    val input = response.body?.byteStream() ?: return@withContext false
                    input.use { stream -> file.outputStream().use { stream.copyTo(it) } }
                }
            voices.normalizeWaveFile(file)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            diagnostics.log("DjRemote", "Remote break generation failed", e); false
        }
    }
}
