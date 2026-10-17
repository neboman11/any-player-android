package com.anyplayer.android.dj

import com.anyplayer.android.data.settings.SyncSettingsStore
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient

/** Shared transaction: verify bytes, install, atomically activate. Failed switches never change
 * the active marker. Every disk operation (including migrations) is confined to IO. */
class ArtifactStore<D : ArtifactDescriptor> internal constructor(
    private val root: File,
    private val voice: Boolean,
    private val client: OkHttpClient,
    private val settings: SyncSettingsStore,
    private val decode: (JsonObject) -> Pair<List<D>, String?>,
    private val diagnostics: DjDiagnostics,
) {
    private val mutableCatalog = MutableStateFlow(ArtifactCatalogState<D>())
    val catalog = mutableCatalog.asStateFlow()
    private val mutableDownload = MutableStateFlow<ArtifactDownloadState>(ArtifactDownloadState.NotDownloaded)
    val download = mutableDownload.asStateFlow()
    private val mutableDownloaded = MutableStateFlow<List<DownloadedArtifact>>(emptyList())
    val downloaded = mutableDownloaded.asStateFlow()
    private val mutex = Mutex()
    private val catalogMutex = Mutex()
    @Volatile private var activeFile: File? = null
    private var initialized = false
    private val endpoint get() = if (voice) "v1/dj-voice-models" else "v1/dj-models"
    private val marker get() = File(root, if (voice) "active-voice.json" else "active-model")
    private val notConfigured get() = if (voice)
        "This sync server doesn't have an AI DJ voice catalog configured yet. Ask the server admin."
        else "This sync server doesn't have an AI DJ model catalog configured yet. Ask the server admin."

    suspend fun initialize() = withContext(Dispatchers.IO) { mutex.withLock { initializeLocked() } }
    private fun initializeLocked() {
        if (initialized) return
        root.mkdirs()
        root.walkTopDown().filter { it.name.endsWith(".part") || (voice && it.name.startsWith('.') && it.name.endsWith(".tmp")) }
            .toList().forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
        if (voice) migrateLegacyVoice()
        activeFile = restoreActive()
        mutableDownload.value = activeFile?.let(ArtifactDownloadState::Ready) ?: ArtifactDownloadState.NotDownloaded
        mutableCatalog.value = mutableCatalog.value.copy(activeId = activeFile?.parentFile?.takeIf { it != root }?.name)
        refreshDownloaded()
        initialized = true
    }
    fun activeFileOrNull(): File? = activeFile
    fun select(id: String?) {
        if (id != null && !SAFE_COMPONENT.matches(id)) return
        mutableCatalog.value = mutableCatalog.value.copy(selectedId = id)
    }
    suspend fun refreshCatalog() = withContext(Dispatchers.IO) { catalogMutex.withLock {
        initialize()
        mutableCatalog.value = mutableCatalog.value.copy(loading = true, error = null)
        try {
            val account = settings.djAccount()
            check(account.base.isNotBlank()) { "Sync server target is not set." }
            client.newCall(account.request(endpoint).build()).awaitDj().use { response ->
                check(response.isSuccessful) { describeFailure(response.code, null, notConfigured) }
                val parsed = Json.parseToJsonElement(checkNotNull(response.body).string()).jsonObject
                val (items, defaultId) = decode(parsed)
                check(validCatalog(items, defaultId)) { "Invalid AI DJ ${if (voice) "voice" else "model"} catalog." }
                mutableCatalog.value = mutableCatalog.value.copy(items = items, defaultId = defaultId, loading = false)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val reason = if (e is java.io.IOException) describeFailure(null, e, notConfigured) else e.message ?: "Invalid AI DJ catalog."
            diagnostics.log("DjArtifacts", reason)
            mutableCatalog.value = mutableCatalog.value.copy(loading = false, error = reason)
        }
    } }

    /** Explicit UI action only: enabling DJ never invokes this method. */
    suspend fun downloadSelected(): Unit = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        var temporary: File? = null
        var staging: File? = null
        try {
            initializeLocked()
            val state = mutableCatalog.value
            val descriptor = state.items.firstOrNull { it.id == (state.selectedId ?: state.defaultId) }
                ?: error("Select an AI DJ ${if (voice) "voice" else "model"} first.")
            check(validCatalog(listOf(descriptor), descriptor.id)) { "Invalid AI DJ artifact descriptor." }
            val destination = destination(descriptor)
            if (usable(destination) && (voice || fileSha256(destination).equals(descriptor.sha256, true))) {
                activate(descriptor, destination); return@withContext
            }
            val part = File(root, ".${descriptor.id}.${descriptor.version}.download.part").also { temporary = it }
            val account = settings.djAccount()
            check(account.base.isNotBlank()) { "Sync server target is not set." }
            mutableDownload.value = ArtifactDownloadState.Downloading(0f)
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L; var lastProgress = System.nanoTime()
            client.newCall(account.request("$endpoint/${descriptor.id}/download").build()).awaitDj().use { response ->
                check(response.isSuccessful) { describeFailure(response.code, null, notConfigured) }
                checkNotNull(response.body).byteStream().use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        count += n
                        check(count <= descriptor.sizeBytes) { "Downloaded size does not match the catalog." }
                        output.write(buffer, 0, n); digest.update(buffer, 0, n)
                        val now = System.nanoTime()
                        if (now - lastProgress >= 250_000_000L) {
                            mutableDownload.value = ArtifactDownloadState.Downloading(if (descriptor.sizeBytes == 0L) 0f else count.toFloat() / descriptor.sizeBytes)
                            lastProgress = now
                        }
                    }
                } }
            }
            check(count == descriptor.sizeBytes) { "Downloaded size does not match the catalog." }
            check(digest.digest().toHex().equals(descriptor.sha256, true)) { "Downloaded SHA-256 does not match the catalog." }
            destination.parentFile?.mkdirs()
            if (voice) {
                val dir = File(root, ".${descriptor.id}.${descriptor.version}.tmp").also { staging = it; it.mkdirs() }
                part.inputStream().use { extractZipSafely(it, dir) }
                check(completeVoice(dir)) { "Voice bundle is incomplete." }
                // The expensive content hash is computed and verified ONCE, before activation.
                val hash = bundleContentSha256(dir)
                File(dir, ".bundle-sha256").writeText(hash)
                check(File(dir, ".bundle-sha256").readText() == hash) { "Voice bundle verification failed." }
                if (destination.exists()) destination.deleteRecursively()
                Files.move(dir.toPath(), destination.toPath(), ATOMIC_MOVE)
            } else {
                Files.move(part.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
            activate(descriptor, destination)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val reason = if (e is java.io.IOException) describeFailure(null, e, notConfigured) else e.message ?: "AI DJ download failed."
            mutableDownload.value = ArtifactDownloadState.Failed(reason)
            diagnostics.log("DjArtifacts", reason)
        } finally {
            temporary?.delete(); staging?.deleteRecursively(); mutex.unlock()
        }
    }
    suspend fun delete(item: DownloadedArtifact): Boolean = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext false
        try {
            initializeLocked()
            val actual = mutableDownloaded.value.firstOrNull { it.id == item.id && it.version == item.version } ?: return@withContext false
            val file = installedFile(actual)
            if (file == activeFile) {
                check(marker.delete() || !marker.exists()) { "Could not clear active artifact." }
                activeFile = null
                mutableCatalog.value = mutableCatalog.value.copy(activeId = null)
                mutableDownload.value = ArtifactDownloadState.NotDownloaded
            }
            val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
            refreshDownloaded(); deleted
        } finally { mutex.unlock() }
    }
    private fun destination(d: D) = if (voice) File(File(root, d.id), d.version)
        else File(File(root, d.id), "${d.version}.${(d as ModelDescriptor).format}")
    private fun activate(d: D, file: File) {
        val content = if (voice) buildJsonObject { put("id", d.id); put("version", d.version) }.toString()
            else "${d.id}/${file.name}"
        atomicMarker(marker, content)
        activeFile = file
        mutableCatalog.value = mutableCatalog.value.copy(activeId = d.id)
        mutableDownload.value = ArtifactDownloadState.Ready(file)
        if (!voice) {
            file.parentFile?.listFiles()?.filter { it != file && it.extension in setOf("task", "litertlm") }?.forEach(File::delete)
            root.listFiles()?.filter { it.isFile && it.extension == "task" }?.forEach(File::delete)
        }
        refreshDownloaded()
    }
    private fun restoreActive(): File? {
        val active = runCatching {
            if (voice) {
                val obj = Json.parseToJsonElement(marker.readText()).jsonObject
                val id = obj.getValue("id").jsonPrimitive.content; val version = obj.getValue("version").jsonPrimitive.content
                require(SAFE_COMPONENT.matches(id) && SAFE_COMPONENT.matches(version))
                File(File(root, id), version)
            } else {
                val pieces = marker.readText().trim().split('/')
                require(pieces.size == 2 && pieces.all { SAFE_COMPONENT.matches(it) })
                File(File(root, pieces[0]), pieces[1])
            }
        }.getOrNull()?.takeIf(::usable)
        return active ?: if (!voice) root.listFiles()?.firstOrNull { it.extension == "task" && regularFile(it) } else null
    }
    private fun usable(file: File) = if (voice) completeVoice(file) else regularFile(file)
    private fun installedFile(item: DownloadedArtifact) = if (item.id == null) File(root, item.version)
        else File(File(root, item.id), item.version)
    private fun refreshDownloaded() {
        val files = root.listFiles().orEmpty().flatMap { id ->
            if (id.isDirectory && SAFE_COMPONENT.matches(id.name)) id.listFiles().orEmpty().filter(::usable).toList()
            else if (!voice && id.extension == "task" && regularFile(id)) listOf(id) else emptyList()
        }
        mutableDownloaded.value = files.map { file -> DownloadedArtifact(
            file.parentFile?.takeIf { it != root }?.name, file.name,
            if (voice) file.walkTopDown().filter(::regularFile).sumOf(File::length) else file.length(), file == activeFile,
        ) }.sortedWith(compareBy({ it.id }, { it.version }))
    }
    private fun migrateLegacyVoice() {
        val oldMarker = File(root, "active-version")
        val old = File(root, "default")
        if (marker.exists() || !oldMarker.isFile || oldMarker.readText().trim() != "default" || !completeVoice(old)) return
        val stage = File(root, ".default.default.tmp")
        stage.mkdirs()
        old.listFiles().orEmpty().filter { it.name != "default" }.forEach { it.copyRecursively(File(stage, it.name), overwrite = true) }
        check(completeVoice(stage)) { "Legacy voice migration is incomplete." }
        val nested = File(old, "default")
        if (!nested.exists()) Files.move(stage.toPath(), nested.toPath(), ATOMIC_MOVE) else stage.deleteRecursively()
        atomicMarker(marker, "{\"id\":\"default\",\"version\":\"default\"}")
        oldMarker.delete()
        old.listFiles().orEmpty().filter { it != nested }.forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
    }
}
internal fun regularFile(file: File) = Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)
internal fun completeVoice(dir: File): Boolean = Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS) &&
    regularFile(File(dir, "tokens.txt")) && dir.listFiles().orEmpty().any { it.extension == "onnx" && regularFile(it) } &&
    File(dir, ".bundle-sha256").let { !it.exists() || (regularFile(it) && SHA256.matches(it.readText().trim())) }
internal fun atomicMarker(file: File, text: String) {
    val temporary = File(file.parentFile, ".${file.name}.tmp")
    try {
        temporary.writeText(text)
        Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { temporary.delete() }
}
internal fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().toHex()
}
internal fun bundleContentSha256(dir: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    dir.walkTopDown().filter { regularFile(it) && it.name != ".bundle-sha256" }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.forEach { file ->
        digest.update(file.relativeTo(dir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
    }
    return digest.digest().toHex()
}
internal fun extractZipSafely(input: InputStream, directory: File) {
    val root = directory.canonicalFile.toPath()
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val output = File(directory, entry.name).canonicalFile
            require(output.toPath().startsWith(root) && output.toPath() != root) { "Unsafe path in voice bundle." }
            if (entry.isDirectory) output.mkdirs() else {
                output.parentFile?.mkdirs()
                check(!Files.isSymbolicLink(output.toPath())) { "Unsafe link in voice bundle." }
                output.outputStream().use { zip.copyTo(it) }
            }
            zip.closeEntry()
        }
    }
}
