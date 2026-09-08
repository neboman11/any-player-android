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
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Downloads the AI DJ on-device LLM model from the user's own sync server
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

        // Mirrors VoiceModelDownloader's SAFE_COMPONENT: the server-supplied version is used
        // to build a file path, so it must be rejected if it could escape modelDir (e.g. a
        // path-traversal segment from a compromised/MITM'd sync server).
        val SAFE_COMPONENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }

    private val modelDir = File(context.filesDir, "dj_models")

    private val mutableDownloadState = MutableStateFlow<DjModelDownloadState>(restoreExistingModel())
    val downloadState: StateFlow<DjModelDownloadState> = mutableDownloadState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private fun restoreExistingModel(): DjModelDownloadState {
        val existing = modelDir.listFiles()?.firstOrNull { it.extension == "task" }
        return if (existing != null) DjModelDownloadState.Ready(existing) else DjModelDownloadState.NotDownloaded
    }

    fun modelFileOrNull(): File? = (mutableDownloadState.value as? DjModelDownloadState.Ready)?.file

    fun startDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch { runDownload() }
    }

    private suspend fun runDownload() {
        mutableDownloadState.value = DjModelDownloadState.Downloading(0f)

        val prefs = syncPreferencesStore.read()
        val base = normalizeSyncServerBaseUrl(prefs.serverTarget)
        if (base.isBlank()) {
            mutableDownloadState.value = DjModelDownloadState.Failed("Sync server is not configured")
            return
        }
        val token = normalizeSyncServerAuthToken(prefs.authToken)

        val infoResult = runCatching {
            okHttpClient.newCall(authorizedSyncServerRequest("$base/v1/dj-model/info", token)).execute()
        }
        val infoResponse = infoResult.getOrNull()
        if (infoResponse == null || !infoResponse.isSuccessful) {
            mutableDownloadState.value = DjModelDownloadState.Failed(
                describeFailedSyncResponse(
                    infoResponse,
                    infoResult.exceptionOrNull(),
                    notConfiguredMessage = "This sync server doesn't have an AI DJ model configured yet. Ask the server admin."
                )
            )
            infoResponse?.close()
            return
        }
        val infoBody = infoResponse.use { it.body?.string() }.orEmpty()
        val info = runCatching { json.parseToJsonElement(infoBody).jsonObject }.getOrNull()
        if (info == null) {
            mutableDownloadState.value = DjModelDownloadState.Failed("Sync server returned an unreadable model info response")
            return
        }
        val version = info["version"]?.jsonPrimitive?.content ?: "unversioned"
        if (!SAFE_COMPONENT.matches(version)) {
            mutableDownloadState.value = DjModelDownloadState.Failed("Sync server returned an invalid model version")
            return
        }
        val expectedSha256 = info["sha256"]?.jsonPrimitive?.content
        val expectedSize = info["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L

        modelDir.mkdirs()
        val finalFile = File(modelDir, "$version.task")
        if (finalFile.exists()) {
            mutableDownloadState.value = DjModelDownloadState.Ready(finalFile)
            return
        }

        // `.part` suffix, no Range resume - a "tap Download again" retry is enough for v1.
        val partFile = File(modelDir, "$version.task.part")
        val downloadResult = runCatching {
            okHttpClient.newCall(authorizedSyncServerRequest("$base/v1/dj-model/download", token)).execute()
        }
        val downloadResponse = downloadResult.getOrNull()
        if (downloadResponse == null || !downloadResponse.isSuccessful) {
            mutableDownloadState.value = DjModelDownloadState.Failed(
                describeFailedSyncResponse(
                    downloadResponse,
                    downloadResult.exceptionOrNull(),
                    notConfiguredMessage = "This sync server doesn't have an AI DJ model configured yet. Ask the server admin."
                )
            )
            downloadResponse?.close()
            return
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val writeResult = runCatching {
            downloadSyncServerResponseToFile(downloadResponse, partFile, expectedSize, digest) { fraction ->
                mutableDownloadState.value = DjModelDownloadState.Downloading(fraction)
            }
        }
        if (writeResult.isFailure) {
            CompatLog.e(TAG, "model download failed", writeResult.exceptionOrNull())
            partFile.delete()
            mutableDownloadState.value = DjModelDownloadState.Failed("Download was interrupted")
            return
        }

        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256 != null && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
            partFile.delete()
            mutableDownloadState.value = DjModelDownloadState.Failed("Downloaded model failed integrity verification")
            return
        }

        if (!partFile.renameTo(finalFile)) {
            mutableDownloadState.value = DjModelDownloadState.Failed("Could not finalize downloaded model file")
            return
        }
        mutableDownloadState.value = DjModelDownloadState.Ready(finalFile)
    }
}
