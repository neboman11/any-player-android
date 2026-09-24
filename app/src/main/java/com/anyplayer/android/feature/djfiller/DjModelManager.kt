package com.anyplayer.android.feature.djfiller

import android.content.Context
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.network.normalizeSyncServerAuthToken
import com.anyplayer.android.core.network.normalizeSyncServerBaseUrl
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
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/** Downloads the AI DJ on-device LLM model from the user's own sync server
 *  (`/v1/dj-model/info` + `/v1/dj-model/download`, see any-player-sync-server) into
 *  app-private storage. [startDownload] is only ever meant to be called from an
 *  explicit user button tap in Settings - enabling the "AI DJ" toggle never triggers
 *  a download on its own. */
@Singleton
class DjModelManager private constructor(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val syncPreferencesStore: SyncPreferencesStore,
    ioDispatcher: CoroutineDispatcher,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        json: Json,
        syncPreferencesStore: SyncPreferencesStore
    ) : this(context, okHttpClient, json, syncPreferencesStore, Dispatchers.IO, Unit)

    internal constructor(
        context: Context,
        okHttpClient: OkHttpClient,
        json: Json,
        syncPreferencesStore: SyncPreferencesStore,
        ioDispatcher: CoroutineDispatcher
    ) : this(context, okHttpClient, json, syncPreferencesStore, ioDispatcher, Unit)

    private companion object {
        const val TAG = "DjModelManager"
    }

    private val modelDir = File(context.filesDir, "dj_models")

    private val mutableDownloadState = MutableStateFlow<DjModelDownloadState>(restoreExistingModel())
    val downloadState: StateFlow<DjModelDownloadState> = mutableDownloadState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
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
        if (expectedSha256 == null || !SHA_256.matches(expectedSha256)) {
            mutableDownloadState.value = DjModelDownloadState.Failed("Sync server did not provide a valid model integrity digest")
            return
        }
        val expectedSize = info["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L

        modelDir.mkdirs()
        val finalFile = File(modelDir, "$version.task")
        if (finalFile.exists()) {
            if (finalFile.sha256OrNull()?.equals(expectedSha256, ignoreCase = true) == true) {
                mutableDownloadState.value = DjModelDownloadState.Ready(finalFile)
                return
            }
            if (!finalFile.delete()) {
                mutableDownloadState.value = DjModelDownloadState.Failed("Existing model failed integrity verification")
                return
            }
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

        val actualSha256 = digest.digest().toHexDigest()
        if (!expectedSha256.equals(actualSha256, ignoreCase = true)) {
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

    private fun File.sha256OrNull(): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHexDigest()
    }.getOrNull()
}
