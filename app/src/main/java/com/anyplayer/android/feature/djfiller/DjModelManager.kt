package com.anyplayer.android.feature.djfiller

import android.content.Context
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.feature.djfiller.model.DjModelDownloadState
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Downloads the AI DJ on-device model from the user's own sync server
 *  (`/v1/dj-model/info` + `/v1/dj-model/download`, see any-player-sync-server) into
 *  app-private storage. [startDownload] is only ever meant to be called from an
 *  explicit user button tap in Settings - enabling the "AI DJ" toggle never triggers
 *  a download on its own. */
@Singleton
class DjModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val syncPreferencesStore: SyncPreferencesStore
) {
    private companion object {
        const val TAG = "DjModelManager"
    }

    private val modelDir = File(context.filesDir, "dj_models")
    private val bearerRegex = Regex("^Bearer\\s+", RegexOption.IGNORE_CASE)

    private val mutableDownloadState = MutableStateFlow<DjModelDownloadState>(restoreExistingModel())
    val downloadState: StateFlow<DjModelDownloadState> = mutableDownloadState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private fun restoreExistingModel(): DjModelDownloadState {
        val existing = modelDir.listFiles()?.firstOrNull { it.extension == "task" }
        return if (existing != null) DjModelDownloadState.Ready(existing) else DjModelDownloadState.NotDownloaded
    }

    fun modelFileOrNull(): File? = (mutableDownloadState.value as? DjModelDownloadState.Ready)?.file

    private fun normalizeBaseUrl(serverTarget: String): String {
        val trimmed = serverTarget.trim().trimEnd('/')
        return when {
            trimmed.isBlank() -> trimmed
            trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
            else -> "https://$trimmed"
        }
    }

    private fun normalizeToken(raw: String): String = bearerRegex.replace(raw.trim(), "")

    private fun authorizedRequest(url: String, token: String): Request = Request.Builder()
        .url(url)
        .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
        .get()
        .build()

    fun startDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch { runDownload() }
    }

    private suspend fun runDownload() {
        mutableDownloadState.value = DjModelDownloadState.Downloading(0f)

        val prefs = syncPreferencesStore.read()
        val base = normalizeBaseUrl(prefs.serverTarget)
        if (base.isBlank()) {
            mutableDownloadState.value = DjModelDownloadState.Failed("Sync server is not configured")
            return
        }
        val token = normalizeToken(prefs.authToken)

        val infoResponse = runCatching {
            okHttpClient.newCall(authorizedRequest("$base/v1/dj-model/info", token)).execute()
        }.getOrNull()
        if (infoResponse == null || !infoResponse.isSuccessful) {
            infoResponse?.close()
            mutableDownloadState.value = DjModelDownloadState.Failed("Could not reach sync server for model info")
            return
        }
        val infoBody = infoResponse.use { it.body?.string() }.orEmpty()
        val info = runCatching { json.parseToJsonElement(infoBody).jsonObject }.getOrNull()
        if (info == null) {
            mutableDownloadState.value = DjModelDownloadState.Failed("Sync server returned an unreadable model info response")
            return
        }
        val version = info["version"]?.jsonPrimitive?.content ?: "unversioned"
        val expectedSha256 = info["sha256"]?.jsonPrimitive?.content
        val expectedSize = info["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L

        modelDir.mkdirs()
        val finalFile = File(modelDir, "$version.task")
        if (finalFile.exists()) {
            mutableDownloadState.value = DjModelDownloadState.Ready(finalFile)
            return
        }
        // ponytail: always restarts from byte 0 rather than resuming a partial
        // `.part` file via Range - the server supports Range for a future client that
        // wants it, but a simple "tap Download again" retry is enough for v1.
        val partFile = File(modelDir, "$version.task.part")

        val downloadResponse = runCatching {
            okHttpClient.newCall(authorizedRequest("$base/v1/dj-model/download", token)).execute()
        }.getOrNull()
        if (downloadResponse == null || !downloadResponse.isSuccessful) {
            downloadResponse?.close()
            mutableDownloadState.value = DjModelDownloadState.Failed("Model download failed")
            return
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val writeResult = runCatching {
            downloadResponse.use { response ->
                val body = response.body ?: error("empty response body")
                val totalBytes = expectedSize.takeIf { it > 0 } ?: body.contentLength()
                var bytesRead = 0L
                partFile.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            bytesRead += read
                            if (totalBytes > 0) {
                                mutableDownloadState.value =
                                    DjModelDownloadState.Downloading(bytesRead.toFloat() / totalBytes)
                            }
                        }
                    }
                }
            }
        }
        if (writeResult.isFailure) {
            CompatLog.e(TAG, "model download write failed", writeResult.exceptionOrNull())
            partFile.delete()
            mutableDownloadState.value = DjModelDownloadState.Failed("Model download was interrupted")
            return
        }

        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256 != null && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
            partFile.delete()
            mutableDownloadState.value = DjModelDownloadState.Failed("Downloaded model failed integrity verification")
            return
        }

        if (!partFile.renameTo(finalFile)) {
            mutableDownloadState.value = DjModelDownloadState.Failed("Could not finalize downloaded model")
            return
        }

        mutableDownloadState.value = DjModelDownloadState.Ready(finalFile)
    }
}
