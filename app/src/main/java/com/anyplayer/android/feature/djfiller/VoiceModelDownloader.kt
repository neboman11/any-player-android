package com.anyplayer.android.feature.djfiller

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.feature.djfiller.model.DjModelDownloadState
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val onVoiceActivated: () -> Unit = {}
) {
    private companion object {
        const val TAG = "VoiceModelDownloader"
        const val ACTIVE_VOICE_FILE = "active-voice.json"
        const val LEGACY_ACTIVE_VERSION_FILE = "active-version"
        val SAFE_COMPONENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA_256 = Regex("[0-9a-fA-F]{64}")
        val ACTIVE_MARKER = Regex("\\{\\\"id\\\":\\\"([A-Za-z0-9][A-Za-z0-9._-]{0,127})\\\",\\\"version\\\":\\\"([A-Za-z0-9][A-Za-z0-9._-]{0,127})\\\"\\}")
    }

    private data class ActiveVoice(val id: String, val version: String)

    private val mutableDownloadState = MutableStateFlow<DjModelDownloadState>(restoreExistingVoice())
    val downloadState: StateFlow<DjModelDownloadState> = mutableDownloadState.asStateFlow()

    private var latestCatalog: DjVoiceCatalog? = null
    private val mutableVoiceState = MutableStateFlow(
        DjVoiceState(selectedId = syncPreferencesStore.selectedDjVoiceId(), activeVoice = activeVoice())
    )
    val voiceState: StateFlow<DjVoiceState> = mutableVoiceState.asStateFlow()

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

    /** Compatibility entry point for the existing Settings button. */
    suspend fun download() = downloadSelectedVoice()

    private suspend fun downloadDescriptor(descriptor: DjVoiceDescriptor) {
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
            response.use { result ->
                val body = result.body ?: error("empty response body")
                val total = descriptor.sizeBytes.takeIf { it > 0 } ?: body.contentLength()
                var readTotal = 0L
                zipFile.outputStream().use { output -> body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        readTotal += read
                        if (total > 0) mutableDownloadState.value = DjModelDownloadState.Downloading(readTotal.toFloat() / total)
                    }
                } }
            }
        }.isSuccess
        if (!downloaded || !descriptor.sha256.equals(digest.digest().toHex(), ignoreCase = true)) {
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

    private fun restoreExistingVoice(): DjModelDownloadState {
        migrateLegacyDefault()
        return voiceDirOrNull()?.let(DjModelDownloadState::Ready) ?: DjModelDownloadState.NotDownloaded
    }

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

    private fun isCompleteVoiceDirectory(directory: File): Boolean =
        directory.isDirectoryNoFollow() &&
            directory.listFiles()?.any { it.extension == "onnx" && it.isRegularFileNoFollow() } == true &&
            File(directory, "tokens.txt").isRegularFileNoFollow()

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
        val value = syncPreferencesStore.read().serverTarget.trim().trimEnd('/')
        if (value.isBlank()) {
            mutableDownloadState.value = DjModelDownloadState.Failed("Sync server is not configured")
            return null
        }
        val url = (if (value.startsWith("https://") || value.startsWith("http://")) value else "https://$value")
            .toHttpUrlOrNull()
        if (url == null) mutableDownloadState.value = DjModelDownloadState.Failed("Sync server target is invalid")
        return url
    }

    private fun authToken(): String = syncPreferencesStore.read().authToken.trim().removePrefix("Bearer ").removePrefix("bearer ")

    private fun authorizedRequest(url: HttpUrl, token: String): Request = Request.Builder().url(url).apply {
        if (token.isNotEmpty()) header("Authorization", "Bearer $token")
    }.get().build()

    private fun describeFailedResponse(response: Response?, exception: Throwable?): String = when (response?.code) {
        null -> "Could not reach sync server. Check Sync Server Target and network connection." + (exception?.message?.let { " ($it)" } ?: "")
        401, 403 -> "Sync server rejected auth token. Check Sync Auth Token setting."
        404 -> "This sync server doesn't have an AI DJ voice catalog configured yet."
        else -> "Sync server returned an error (HTTP ${response.code})."
    }

    private fun updateVoiceState(
        catalog: DjVoiceCatalog? = mutableVoiceState.value.catalog,
        selectedId: String? = mutableVoiceState.value.selectedId,
        activeVoice: DjVoiceDescriptor? = mutableVoiceState.value.activeVoice
    ) {
        latestCatalog = catalog
        mutableVoiceState.value = DjVoiceState(catalog, selectedId, activeVoice)
    }

    private fun File.readTextOrNull(): String? = takeIf { it.isRegularFileNoFollow() }?.runCatching(File::readText)?.getOrNull()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
