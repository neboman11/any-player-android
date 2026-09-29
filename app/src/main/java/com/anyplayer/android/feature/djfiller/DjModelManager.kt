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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DjScriptModelCatalog(
    @SerialName("default_id") val defaultId: String? = null,
    val models: List<DjScriptModelDescriptor> = emptyList()
)

/** A server-hosted script model. [format] is the file extension and picks the on-device
 *  runtime: MediaPipe for `task`, LiteRT-LM for `litertlm` (see [DjScriptGenerator]). */
@Serializable
data class DjScriptModelDescriptor(
    val id: String,
    val name: String,
    val version: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
    val format: String
)

/** [activeId] is null both when nothing is downloaded and for a model file downloaded
 *  before the catalog existed (see [DjModelManager.restoreActiveModel]). */
data class DjScriptModelState(
    val catalog: DjScriptModelCatalog? = null,
    val selectedId: String? = null,
    val activeId: String? = null,
    val catalogError: String? = null
)

/** Downloads AI DJ on-device script-generation models from the user's own sync server's
 *  catalog (`/v1/dj-models` + `/v1/dj-models/{id}/download`, see any-player-sync-server)
 *  into app-private storage as `dj_models/<id>/<version>.<format>`. Selecting a model only
 *  saves its ID; [startDownload] is only ever meant to be called from an explicit user
 *  button tap in Settings - enabling the "AI DJ" toggle never triggers a download. */
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
        const val ACTIVE_MODEL_FILE = "active-model"
        const val LEGACY_FORMAT = "task"
        val FORMATS = setOf(LEGACY_FORMAT, "litertlm")
        const val NOT_CONFIGURED = "This sync server doesn't have an AI DJ model catalog configured yet. Ask the server admin."
    }

    private val modelDir = File(context.filesDir, "dj_models")

    // Tracked apart from downloadState so a failed catalog refresh or a failed switch to
    // another model never takes the already-active model away from DjScriptGenerator.
    @Volatile
    private var activeFile: File? = null

    private val mutableDownloadState = MutableStateFlow(restoreActiveModel())
    val downloadState: StateFlow<DjModelDownloadState> = mutableDownloadState.asStateFlow()

    private val mutableModelState = MutableStateFlow(
        DjScriptModelState(selectedId = syncPreferencesStore.selectedDjModelId(), activeId = activeMarker()?.first)
    )
    val modelState: StateFlow<DjScriptModelState> = mutableModelState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var downloadJob: Job? = null

    /** The active model is named by the `active-model` marker (`<id>/<version>.<format>`);
     *  without one, a root-level `*.task` file from before the catalog is still used. */
    private fun restoreActiveModel(): DjModelDownloadState {
        val existing = activeMarker()?.let { (id, fileName) -> File(File(modelDir, id), fileName) }?.takeIf(File::isFile)
            ?: modelDir.listFiles()?.firstOrNull { it.isFile && it.extension == LEGACY_FORMAT }
        activeFile = existing
        return if (existing != null) DjModelDownloadState.Ready(existing) else DjModelDownloadState.NotDownloaded
    }

    fun modelFileOrNull(): File? = activeFile

    fun refreshCatalog() {
        scope.launch { fetchCatalog() }
    }

    /** Saves only a catalog ID; it never starts network work. */
    fun selectModel(id: String) {
        if (mutableModelState.value.catalog?.models?.any { it.id == id } != true) return
        syncPreferencesStore.setSelectedDjModelId(id)
        mutableModelState.value = mutableModelState.value.copy(selectedId = id)
    }

    fun startDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch { runDownload() }
    }

    private fun fetchCatalog(): DjScriptModelCatalog? {
        val failure = { reason: String ->
            mutableModelState.value = mutableModelState.value.copy(catalog = null, catalogError = reason)
            null
        }
        val base = normalizeSyncServerBaseUrl(syncPreferencesStore.read().serverTarget)
        if (base.isBlank()) return failure("Sync server is not configured")
        val token = normalizeSyncServerAuthToken(syncPreferencesStore.read().authToken)
        val result = runCatching {
            okHttpClient.newCall(authorizedSyncServerRequest("$base/v1/dj-models", token)).execute()
        }
        val response = result.getOrNull()
        if (response == null || !response.isSuccessful) {
            response?.close()
            return failure(describeFailedSyncResponse(response, result.exceptionOrNull(), NOT_CONFIGURED))
        }
        val catalog = response.use { it.body?.string() }
            ?.let { body -> runCatching { json.decodeFromString<DjScriptModelCatalog>(body) }.getOrNull() }
            ?.takeIf(::validCatalog)
            ?: return failure("Sync server returned an invalid model catalog")
        mutableModelState.value = mutableModelState.value.copy(catalog = catalog, catalogError = null)
        return catalog
    }

    private fun runDownload() {
        val catalog = mutableModelState.value.catalog ?: fetchCatalog()
        if (catalog == null) {
            mutableDownloadState.value = DjModelDownloadState.Failed(mutableModelState.value.catalogError ?: NOT_CONFIGURED)
            return
        }
        val descriptor = catalog.models.firstOrNull { it.id == (syncPreferencesStore.selectedDjModelId() ?: catalog.defaultId) }
        if (descriptor == null) {
            mutableDownloadState.value = DjModelDownloadState.Failed("Select an AI DJ model before downloading")
            return
        }
        if (syncPreferencesStore.selectedDjModelId() == null) selectModel(descriptor.id)
        val result = downloadDescriptor(descriptor)
        mutableDownloadState.value = result
        if (result is DjModelDownloadState.Failed) CompatLog.w(TAG, "model download failed: ${result.reason}")
    }

    private fun downloadDescriptor(descriptor: DjScriptModelDescriptor): DjModelDownloadState {
        mutableDownloadState.value = DjModelDownloadState.Downloading(0f)
        val prefs = syncPreferencesStore.read()
        val base = normalizeSyncServerBaseUrl(prefs.serverTarget)
        if (base.isBlank()) return DjModelDownloadState.Failed("Sync server is not configured")
        val token = normalizeSyncServerAuthToken(prefs.authToken)

        val idDir = File(modelDir, descriptor.id).apply { mkdirs() }
        val fileName = "${descriptor.version}.${descriptor.format}"
        val finalFile = File(idDir, fileName)
        if (finalFile.exists()) {
            if (finalFile.sha256OrNull()?.equals(descriptor.sha256, ignoreCase = true) == true) {
                return activate(descriptor, finalFile)
            }
            if (!finalFile.delete()) return DjModelDownloadState.Failed("Existing model failed integrity verification")
        }

        // `.part` suffix, no Range resume - a "tap Download again" retry is enough for v1.
        val partFile = File(idDir, "$fileName.part")
        val downloadResult = runCatching {
            okHttpClient.newCall(authorizedSyncServerRequest("$base/v1/dj-models/${descriptor.id}/download", token)).execute()
        }
        val downloadResponse = downloadResult.getOrNull()
        if (downloadResponse == null || !downloadResponse.isSuccessful) {
            downloadResponse?.close()
            return DjModelDownloadState.Failed(describeFailedSyncResponse(downloadResponse, downloadResult.exceptionOrNull(), NOT_CONFIGURED))
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val writeResult = runCatching {
            downloadSyncServerResponseToFile(downloadResponse, partFile, descriptor.sizeBytes, digest) { fraction ->
                mutableDownloadState.value = DjModelDownloadState.Downloading(fraction)
            }
        }
        if (writeResult.isFailure) {
            CompatLog.e(TAG, "model download failed", writeResult.exceptionOrNull())
            partFile.delete()
            return DjModelDownloadState.Failed("Download was interrupted")
        }
        if (!descriptor.sha256.equals(digest.digest().toHexDigest(), ignoreCase = true)) {
            partFile.delete()
            return DjModelDownloadState.Failed("Downloaded model failed integrity verification")
        }
        if (!partFile.renameTo(finalFile)) return DjModelDownloadState.Failed("Could not finalize downloaded model file")
        return activate(descriptor, finalFile)
    }

    /** Points the marker at [file], then deletes superseded files: older versions of this
     *  model and any pre-catalog root-level model. Other catalog models stay on disk so
     *  switching back doesn't mean another multi-GB download. */
    private fun activate(descriptor: DjScriptModelDescriptor, file: File): DjModelDownloadState {
        val marker = File(modelDir, ACTIVE_MODEL_FILE)
        val temporary = File(modelDir, ".$ACTIVE_MODEL_FILE.tmp")
        val written = runCatching {
            temporary.writeText("${descriptor.id}/${file.name}")
            check(temporary.renameTo(marker)) { "could not replace active model marker" }
        }.onFailure { temporary.delete() }.isSuccess
        if (!written) return DjModelDownloadState.Failed("Downloaded model could not be activated")
        activeFile = file
        mutableModelState.value = mutableModelState.value.copy(activeId = descriptor.id)
        file.parentFile?.listFiles()?.filter { it != file && it.isFile }?.forEach(File::delete)
        modelDir.listFiles()?.filter { it.isFile && it.extension == LEGACY_FORMAT }?.forEach(File::delete)
        return DjModelDownloadState.Ready(file)
    }

    private fun activeMarker(): Pair<String, String>? {
        val parts = runCatching { File(modelDir, ACTIVE_MODEL_FILE).readText().trim() }.getOrNull()?.split('/') ?: return null
        return parts.takeIf { it.size == 2 && it.all(SAFE_COMPONENT::matches) }?.let { it[0] to it[1] }
    }

    private fun validCatalog(catalog: DjScriptModelCatalog): Boolean =
        catalog.models.all { model ->
            SAFE_COMPONENT.matches(model.id) && SAFE_COMPONENT.matches(model.version) && model.name.isNotBlank() &&
                model.sizeBytes >= 0 && SHA_256.matches(model.sha256) && model.format in FORMATS
        } &&
            catalog.models.map(DjScriptModelDescriptor::id).distinct().size == catalog.models.size &&
            (catalog.defaultId == null || catalog.models.any { it.id == catalog.defaultId })

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
