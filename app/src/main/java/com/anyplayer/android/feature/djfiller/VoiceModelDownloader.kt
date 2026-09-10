package com.anyplayer.android.feature.djfiller

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.network.normalizeSyncServerAuthToken
import com.anyplayer.android.core.network.normalizeSyncServerBaseUrl
import com.anyplayer.android.feature.djfiller.model.DjModelDownloadState
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import java.util.zip.ZipInputStream

internal fun File.isRegularFileNoFollow(): Boolean =
    runCatching { Files.isRegularFile(toPath(), LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)

private fun File.isDirectoryNoFollow(): Boolean =
    runCatching { Files.isDirectory(toPath(), LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)

internal fun voiceDownloadUrl(base: String, id: String): HttpUrl =
    requireNotNull(base.toHttpUrlOrNull()) { "invalid sync server URL" }
        .newBuilder()
        .addPathSegment("v1")
        .addPathSegment("dj-voice-models")
        .addPathSegment(id)
        .addPathSegment("download")
        .build()

internal fun extractZipSafely(input: InputStream, destination: File) {
    val root = destination.canonicalFile
    check(root.mkdirs() || root.isDirectory) { "could not create extraction directory" }
    val rootPath = root.toPath()
    ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val output = File(root, entry.name).canonicalFile
            check(output != root && output.toPath().startsWith(rootPath)) { "zip entry escapes destination" }
            if (entry.isDirectory) {
                check(output.mkdirs() || output.isDirectory) { "could not create zip directory" }
            } else {
                val parent = output.parentFile
                check(parent != null && (parent.mkdirs() || parent.isDirectory)) {
                    "could not create zip parent directory"
                }
                output.outputStream().use(zip::copyTo)
            }
        }
    }
}

@Serializable
data class DjVoiceCatalog(@SerialName("default_id") val defaultId: String? = null, val voices: List<DjVoiceDescriptor> = emptyList())

@Serializable
data class DjVoiceDescriptor(
    val id: String,
    val name: String,
    val version: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String
)

data class DjVoiceState(
    val catalog: DjVoiceCatalog? = null,
    val selectedId: String? = null,
    val activeVoice: DjVoiceDescriptor? = null
)

/** Downloads only catalog-described AI DJ voices into an app-private cache. */
class VoiceModelDownloader(
    val voiceRootDir: File,
    val okHttpClient: OkHttpClient,
    private val json: Json,
    private val syncPreferencesStore: SyncPreferencesStore,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onVoiceActivated: () -> Unit = {}
) {
    private companion object {
        const val TAG = "VoiceModelDownloader"
        const val ACTIVE_VOICE_FILE = "active-voice.json"
        const val LEGACY_ACTIVE_VERSION_FILE = "active-version"
        // Content hash of a voice bundle's own files, written once right after a verified
        // extraction/activation so later completeness checks (voiceDirOrNull(), the fast
        // path in downloadDescriptor) can detect on-disk corruption/tampering instead of
        // just trusting that the expected filenames are present - mirrors the re-verification
        // DjModelManager.runDownload does against its single model file's sha256.
        const val BUNDLE_HASH_FILE = ".bundle-sha256"
        val ACTIVE_MARKER = Regex("\\{\\\"id\\\":\\\"([A-Za-z0-9][A-Za-z0-9._-]{0,127})\\\",\\\"version\\\":\\\"([A-Za-z0-9][A-Za-z0-9._-]{0,127})\\\"\\}")
    }

    private data class ActiveVoice(val id: String, val version: String)

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // This class is constructed eagerly (see DjVoiceSynthesizer) from a chain reachable from
    // MainActivity.onCreate on the main thread. The migration below can copyRecursively a
    // whole legacy voice directory, so the initial value here must stay cheap (just a marker
    // check, no migration) - migrateLegacyDefault() runs in the background instead and
    // updates the flows afterward if it changes anything.
    private val mutableDownloadState = MutableStateFlow<DjModelDownloadState>(currentVoiceState())
    val downloadState: StateFlow<DjModelDownloadState> = mutableDownloadState.asStateFlow()

    private var latestCatalog: DjVoiceCatalog? = null
    private val mutableVoiceState = MutableStateFlow(
        DjVoiceState(selectedId = syncPreferencesStore.selectedDjVoiceId(), activeVoice = activeVoice())
    )
    val voiceState: StateFlow<DjVoiceState> = mutableVoiceState.asStateFlow()

    init {
        scope.launch {
            runCatching { migrateLegacyDefault() }
                .onFailure { CompatLog.e(TAG, "failed to migrate legacy AI DJ voice", it) }
            val migrated = currentVoiceState()
            if (mutableDownloadState.value != migrated) {
                mutableDownloadState.value = migrated
            }
            if (mutableVoiceState.value.activeVoice == null) {
                activeVoice()?.let { updateVoiceState(activeVoice = it) }
            }
        }
    }

    fun voiceDirOrNull(): File? = activeVoiceMarker()
        ?.let { voiceDirectory(it.id, it.version) }
        ?.takeIf(::isCompleteVoiceDirectory)

    fun activeVoice(): DjVoiceDescriptor? {
        val active = activeVoiceMarker() ?: return null
        return latestCatalog?.voices?.firstOrNull { it.id == active.id && it.version == active.version }
            ?: DjVoiceDescriptor(active.id, active.id, active.version, 0, "")
    }

    /** Saves only a catalog ID; it never accepts a URL or starts network work. */
    fun selectVoice(descriptor: DjVoiceDescriptor) {
        require(validDescriptor(descriptor)) { "invalid catalog voice descriptor" }
        require(descriptor in (mutableVoiceState.value.catalog?.voices ?: emptyList())) {
            "voice must come from the current catalog"
        }
        syncPreferencesStore.setSelectedDjVoiceId(descriptor.id)
        updateVoiceState(selectedId = descriptor.id)
    }

    /** Testable activation seam: applies the same complete-bundle and atomic-marker checks. */
    internal fun activate(descriptor: DjVoiceDescriptor, bundleDirectory: File) {
        require(validDescriptor(descriptor)) { "invalid catalog voice descriptor" }
        check(isCompleteVoiceDirectory(bundleDirectory)) { "voice bundle missing its ONNX model or tokens.txt" }
        val staging = stagingDirectory(descriptor)
        staging.deleteRecursively()
        try {
            check(bundleDirectory.isDirectory && bundleDirectory.copyRecursively(staging, overwrite = true)) { "could not stage voice bundle" }
            check(isCompleteVoiceDirectory(staging)) { "voice bundle is missing its ONNX model or tokens.txt" }
            writeBundleHashMarker(staging)
            activateStaged(descriptor, staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun refreshCatalog(): DjVoiceCatalog? {
        val base = configuredBaseUrl() ?: return null
        val responseResult = runCatching {
            val url = base.newBuilder()
                .addPathSegment("v1")
                .addPathSegment("dj-voice-models")
                .build()
            okHttpClient.newCall(authorizedRequest(url, authToken())).execute()
        }
        val response = responseResult.getOrNull()
        if (response == null || !response.isSuccessful) {
            mutableDownloadState.value = DjModelDownloadState.Failed(describeFailedResponse(response, responseResult.exceptionOrNull()))
            response?.close()
            return null
        }
        val catalog = response.use { result ->
            result.body?.string()?.let { body -> runCatching { json.decodeFromString<DjVoiceCatalog>(body) }.getOrNull() }
        }
        if (catalog == null || !validCatalog(catalog)) {
            mutableDownloadState.value = DjModelDownloadState.Failed("Sync server returned an invalid voice catalog")
            return null
        }
        updateVoiceState(catalog = catalog)
        return catalog
    }

    suspend fun downloadSelectedVoice() {
        val catalog = mutableVoiceState.value.catalog ?: refreshCatalog() ?: return
        val selectedId = syncPreferencesStore.selectedDjVoiceId() ?: catalog.defaultId
        val descriptor = catalog.voices.firstOrNull { it.id == selectedId }
        if (descriptor == null) {
            mutableDownloadState.value = DjModelDownloadState.Failed("Select an AI DJ voice before downloading")
            return
        }
        if (syncPreferencesStore.selectedDjVoiceId() == null) {
            syncPreferencesStore.setSelectedDjVoiceId(descriptor.id)
            updateVoiceState(selectedId = descriptor.id)
        }
        downloadDescriptor(descriptor)
    }

    private suspend fun downloadDescriptor(descriptor: DjVoiceDescriptor) {
        // Skip the network round-trip + hash/unzip entirely when this exact (id, version) is
        // already downloaded and verified on disk - mirrors DjModelManager.runDownload's
        // existing-file check, just performed before hitting the network instead of after,
        // since (unlike the model) the voice descriptor is already fully known up front.
        val alreadyInstalled = voiceDirectory(descriptor.id, descriptor.version)
        if (isCompleteVoiceDirectory(alreadyInstalled)) {
            check(writeActiveVoice(ActiveVoice(descriptor.id, descriptor.version))) { "could not activate downloaded voice" }
            mutableDownloadState.value = DjModelDownloadState.Ready(alreadyInstalled)
            updateVoiceState(activeVoice = descriptor)
            onVoiceActivated()
            return
        }
        mutableDownloadState.value = DjModelDownloadState.Downloading(0f)
        val base = configuredBaseUrl() ?: return
        val zipFile = File(voiceRootDir, ".${descriptor.id}.${descriptor.version}.zip.part")
        val staging = stagingDirectory(descriptor)
        voiceRootDir.mkdirs()
        zipFile.delete()
        staging.deleteRecursively()
        val responseResult = runCatching {
            okHttpClient.newCall(authorizedRequest(voiceDownloadUrl(base.toString(), descriptor.id), authToken())).execute()
        }
        val response = responseResult.getOrNull()
        if (response == null || !response.isSuccessful) {
            mutableDownloadState.value = DjModelDownloadState.Failed(describeFailedResponse(response, responseResult.exceptionOrNull()))
            response?.close()
            return
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val downloaded = runCatching {
            downloadSyncServerResponseToFile(response, zipFile, descriptor.sizeBytes, digest) { fraction ->
                mutableDownloadState.value = DjModelDownloadState.Downloading(fraction)
            }
        }.isSuccess
        if (!downloaded || !descriptor.sha256.equals(digest.digest().toHexDigest(), ignoreCase = true)) {
            zipFile.delete()
            mutableDownloadState.value = DjModelDownloadState.Failed("Downloaded voice bundle failed integrity verification")
            return
        }
        val extracted = runCatching { extractZip(zipFile, staging) }.isSuccess
        zipFile.delete()
        if (!extracted || !isCompleteVoiceDirectory(staging)) {
            staging.deleteRecursively()
            mutableDownloadState.value = DjModelDownloadState.Failed("Downloaded voice bundle could not be extracted")
            return
        }
        writeBundleHashMarker(staging)
        runCatching { activateStaged(descriptor, staging) }
            .onFailure { CompatLog.e(TAG, "failed to activate AI DJ voice", it) }
            .onSuccess { mutableDownloadState.value = DjModelDownloadState.Ready(voiceDirectory(descriptor.id, descriptor.version)) }
            .onFailure { mutableDownloadState.value = DjModelDownloadState.Failed("Downloaded voice could not be activated") }
        staging.deleteRecursively()
    }

    private fun activateStaged(descriptor: DjVoiceDescriptor, staging: File) {
        check(isCompleteVoiceDirectory(staging)) { "voice bundle is incomplete" }
        val destination = voiceDirectory(descriptor.id, descriptor.version)
        destination.parentFile?.mkdirs()
        if (!isCompleteVoiceDirectory(destination)) {
            destination.deleteRecursively()
            check(staging.renameTo(destination)) { "could not finalize voice bundle" }
        }
        check(writeActiveVoice(ActiveVoice(descriptor.id, descriptor.version))) { "could not activate downloaded voice" }
        mutableDownloadState.value = DjModelDownloadState.Ready(destination)
        updateVoiceState(activeVoice = descriptor)
        onVoiceActivated()
    }

    private fun currentVoiceState(): DjModelDownloadState =
        voiceDirOrNull()?.let(DjModelDownloadState::Ready) ?: DjModelDownloadState.NotDownloaded

    private fun migrateLegacyDefault() {
        val marker = File(voiceRootDir, ACTIVE_VOICE_FILE)
        val legacy = File(voiceRootDir, LEGACY_ACTIVE_VERSION_FILE)
        if (marker.isFile || legacy.readTextOrNull()?.trim() != "default") return
        val oldDirectory = File(voiceRootDir, "default")
        if (!isCompleteVoiceDirectory(oldDirectory)) return
        val destination = voiceDirectory("default", "default")
        if (!isCompleteVoiceDirectory(destination)) {
            val staging = File(voiceRootDir, ".default.default.tmp")
            staging.deleteRecursively()
            if (!oldDirectory.copyRecursively(staging, overwrite = true) || !isCompleteVoiceDirectory(staging)) return
            destination.parentFile?.mkdirs()
            if (!staging.renameTo(destination)) return
        }
        writeActiveVoice(ActiveVoice("default", "default"))
    }

    private fun activeVoiceMarker(): ActiveVoice? {
        val marker = File(voiceRootDir, ACTIVE_VOICE_FILE)
        val match = marker.readTextOrNull()?.trim()?.let(ACTIVE_MARKER::matchEntire) ?: return null
        return ActiveVoice(match.groupValues[1], match.groupValues[2])
    }

    private fun writeActiveVoice(active: ActiveVoice): Boolean {
        voiceRootDir.mkdirs()
        val marker = File(voiceRootDir, ACTIVE_VOICE_FILE)
        val temporary = File(voiceRootDir, ".$ACTIVE_VOICE_FILE.tmp")
        if (temporary.exists() && !temporary.isRegularFileNoFollow()) return false
        return runCatching {
            temporary.writeText("{\"id\":\"${active.id}\",\"version\":\"${active.version}\"}")
            check(temporary.renameTo(marker)) { "could not replace active marker" }
        }.onFailure { temporary.delete() }.isSuccess
    }

    private fun voiceDirectory(id: String, version: String): File {
        requireSafeComponent(id)
        requireSafeComponent(version)
        return File(File(voiceRootDir, id), version)
    }

    private fun stagingDirectory(descriptor: DjVoiceDescriptor): File {
        requireSafeComponent(descriptor.id)
        requireSafeComponent(descriptor.version)
        return File(voiceRootDir, ".${descriptor.id}.${descriptor.version}.tmp")
    }

    private fun isCompleteVoiceDirectory(directory: File): Boolean {
        if (!directory.isDirectoryNoFollow()) return false
        val hasOnnx = directory.listFiles()?.any { it.extension == "onnx" && it.isRegularFileNoFollow() } == true
        val hasTokens = File(directory, "tokens.txt").isRegularFileNoFollow()
        if (!hasOnnx || !hasTokens) return false
        // No marker means a bundle predating this check (e.g. a legacy-migrated directory) -
        // accept it as before rather than retroactively invalidating it. Once a marker exists,
        // it must match the bundle's current content or the directory is treated as corrupt.
        val expectedHash = File(directory, BUNDLE_HASH_FILE).readTextOrNull()?.trim() ?: return true
        return expectedHash.equals(directory.bundleContentSha256(), ignoreCase = true)
    }

    private fun writeBundleHashMarker(directory: File) {
        val hash = directory.bundleContentSha256() ?: return
        runCatching { File(directory, BUNDLE_HASH_FILE).writeText(hash) }
    }

    /** Content hash of a flat voice bundle directory (file names + bytes, sorted for
     *  determinism), excluding [BUNDLE_HASH_FILE] itself. Not comparable to a descriptor's
     *  [DjVoiceDescriptor.sha256] (that's the pre-extraction zip's hash) - this only detects
     *  drift in the bundle's own on-disk content since it was last verified. */
    private fun File.bundleContentSha256(): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        listFiles()
            ?.filter { it.isRegularFileNoFollow() && it.name != BUNDLE_HASH_FILE }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                digest.update(file.name.toByteArray())
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        digest.digest().toHexDigest()
    }.getOrNull()

    private fun extractZip(zipFile: File, destination: File) {
        zipFile.inputStream().use { extractZipSafely(it, destination) }
    }

    private fun validCatalog(catalog: DjVoiceCatalog): Boolean =
        catalog.defaultId?.let(SAFE_COMPONENT::matches) != false && catalog.voices.all(::validDescriptor) &&
            catalog.voices.map(DjVoiceDescriptor::id).distinct().size == catalog.voices.size &&
            (catalog.defaultId == null || catalog.voices.any { it.id == catalog.defaultId })

    private fun validDescriptor(descriptor: DjVoiceDescriptor): Boolean =
        SAFE_COMPONENT.matches(descriptor.id) && SAFE_COMPONENT.matches(descriptor.version) && descriptor.name.isNotBlank() &&
            descriptor.sizeBytes >= 0 && SHA_256.matches(descriptor.sha256)

    private fun requireSafeComponent(value: String) = require(SAFE_COMPONENT.matches(value)) { "unsafe voice cache component" }

    private fun configuredBaseUrl(): HttpUrl? {
        val normalized = normalizeSyncServerBaseUrl(syncPreferencesStore.read().serverTarget)
        if (normalized.isBlank()) {
            mutableDownloadState.value = DjModelDownloadState.Failed("Sync server is not configured")
            return null
        }
        val url = normalized.toHttpUrlOrNull()
        if (url == null) mutableDownloadState.value = DjModelDownloadState.Failed("Sync server target is invalid")
        return url
    }

    private fun authToken(): String = normalizeSyncServerAuthToken(syncPreferencesStore.read().authToken)

    private fun authorizedRequest(url: HttpUrl, token: String): Request =
        authorizedSyncServerRequest(url.toString(), token)

    private fun describeFailedResponse(response: Response?, exception: Throwable?): String =
        describeFailedSyncResponse(
            response,
            exception,
            notConfiguredMessage = "This sync server doesn't have an AI DJ voice catalog configured yet."
        )

    private fun updateVoiceState(
        catalog: DjVoiceCatalog? = mutableVoiceState.value.catalog,
        selectedId: String? = mutableVoiceState.value.selectedId,
        activeVoice: DjVoiceDescriptor? = mutableVoiceState.value.activeVoice
    ) {
        latestCatalog = catalog
        mutableVoiceState.value = DjVoiceState(catalog, selectedId, activeVoice)
    }

    private fun File.readTextOrNull(): String? = takeIf { it.isRegularFileNoFollow() }?.runCatching(File::readText)?.getOrNull()
}
