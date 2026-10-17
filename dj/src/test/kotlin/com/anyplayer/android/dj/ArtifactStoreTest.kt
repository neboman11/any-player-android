package com.anyplayer.android.dj

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtifactStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var root: File
    private lateinit var sync: FakeSync
    private lateinit var models: ArtifactStore<ModelDescriptor>
    @Before fun setup() {
        server.start(); sync = FakeSync(server.url("/").toString()); root = temporary.newFolder("models")
        models = ArtifactStore(root, false, OkHttpClient(), sync, { obj ->
            obj.getValue("models").jsonArray.map { Json.decodeFromJsonElement<ModelDescriptor>(it) } to obj["default_id"]?.jsonPrimitive?.contentOrNull
        }, DjDiagnostics())
    }
    @After fun close() { server.shutdown() }
    private fun model(id: String = "one", bytes: ByteArray = "model".toByteArray(), version: String = "1") =
        ModelDescriptor(id, "Model $id", version, bytes.size.toLong(), sha256(bytes), "task")
    private fun catalog(vararg descriptors: ModelDescriptor) {
        server.enqueue(MockResponse().setBody(buildJsonObject {
            put("default_id", descriptors.first().id); put("models", Json.encodeToJsonElement(descriptors.toList()))
        }.toString()))
    }
    private fun bytes(bytes: ByteArray) { server.enqueue(MockResponse().setBody(Buffer().write(bytes))) }
    @Test fun `valid download activates exact legacy marker atomically`() = runBlocking {
        val data = "model bytes".toByteArray(); val d = model(bytes = data)
        catalog(d); models.refreshCatalog(); bytes(data); models.downloadSelected()
        assertTrue(models.download.value is ArtifactDownloadState.Ready)
        assertEquals("one/1.task", File(root, "active-model").readText())
        assertFalse(File(root, ".active-model.tmp").exists())
        assertArrayEquals(data, models.activeFileOrNull()!!.readBytes())
        assertEquals(1, models.downloaded.value.size)
        assertTrue(models.downloaded.value.single().active)
    }
    @Test fun `SHA mismatch leaves no activated artifact`() = runBlocking {
        val d = model(); catalog(d); models.refreshCatalog(); bytes("wrong".toByteArray()); models.downloadSelected()
        assertTrue(models.download.value is ArtifactDownloadState.Failed)
        assertTrue((models.download.value as ArtifactDownloadState.Failed).reason.contains("SHA-256"))
        assertFalse(File(root, "active-model").exists()); assertNull(models.activeFileOrNull())
        assertTrue(root.walkTopDown().none { it.extension == "part" })
    }
    @Test fun `short and oversized bodies fail exact size check`() = runBlocking {
        for (body in listOf("x", "too much data")) {
            catalog(model()); models.refreshCatalog(); bytes(body.toByteArray()); models.downloadSelected()
            assertTrue((models.download.value as ArtifactDownloadState.Failed).reason.contains("size"))
            assertNull(models.activeFileOrNull())
        }
    }
    @Test fun `failed switch keeps previous active model and marker`() = runBlocking {
        val old = model(); catalog(old); models.refreshCatalog(); bytes("model".toByteArray()); models.downloadSelected()
        val previous = models.activeFileOrNull()
        val next = model("two"); catalog(old, next); models.refreshCatalog(); models.select("two")
        bytes("wrong".toByteArray()); models.downloadSelected()
        assertEquals(previous, models.activeFileOrNull()); assertTrue(previous!!.isFile)
        assertEquals("one/1.task", File(root, "active-model").readText())
        assertEquals("one", models.catalog.value.activeId)
    }
    @Test fun `existing matching file switches without another download`() = runBlocking {
        val d = model(); File(root, "one").mkdirs(); File(root, "one/1.task").writeText("model")
        catalog(d); models.refreshCatalog(); models.downloadSelected()
        assertEquals(1, server.requestCount); assertEquals("one/1.task", File(root, "active-model").readText())
    }
    @Test fun `legacy root task restored without marker then removed on catalog activation`() = runBlocking {
        File(root, "old.task").writeText("legacy"); File(root, "orphan.part").writeText("part")
        models.initialize(); assertEquals("old.task", models.activeFileOrNull()!!.name)
        assertNull(models.downloaded.value.single().id); assertFalse(File(root, "orphan.part").exists())
        catalog(model()); models.refreshCatalog(); bytes("model".toByteArray()); models.downloadSelected()
        assertFalse(File(root, "old.task").exists())
    }
    @Test fun `invalid catalogs rejected before any artifact request`() = runBlocking {
        for (descriptor in listOf(model().copy(id = "../escape"), model().copy(version = "."), model().copy(sha256 = "bad"),
            model().copy(sizeBytes = -1), model().copy(format = "bin"), model().copy(name = " "))) {
            catalog(descriptor); models.refreshCatalog()
            assertNotNull(models.catalog.value.error)
        }
        assertFalse(validCatalog(listOf(model(), model()), null))
        assertFalse(validCatalog(listOf(model()), "unknown"))
    }
    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip -> for ((name, contents) in files) {
            zip.putNextEntry(ZipEntry(name)); zip.write(contents.toByteArray()); zip.closeEntry()
        } }; output.toByteArray()
    }
    private fun voices(root: File) = ArtifactStore(root, true, OkHttpClient(), sync, { obj ->
        obj.getValue("voices").jsonArray.map { Json.decodeFromJsonElement<VoiceDescriptor>(it) } to "voice"
    }, DjDiagnostics())
    private fun voiceCatalog(bundle: ByteArray) {
        val d = VoiceDescriptor("voice", "Voice", "1", bundle.size.toLong(), sha256(bundle))
        server.enqueue(MockResponse().setBody(buildJsonObject { put("voices", Json.encodeToJsonElement(listOf(d))) }.toString()))
    }
    @Test fun `voice bundle verified once then marker and presence only`() = runBlocking {
        val dir = temporary.newFolder("voices"); val store = voices(dir)
        val bundle = zip("model.onnx" to "onnx", "tokens.txt" to "tokens")
        voiceCatalog(bundle); store.refreshCatalog(); bytes(bundle); store.downloadSelected()
        val active = store.activeFileOrNull()!!
        assertTrue(completeVoice(active)); assertTrue(File(active, ".bundle-sha256").isFile)
        assertEquals("{\"id\":\"voice\",\"version\":\"1\"}", File(dir, "active-voice.json").readText())
        // Re-opening never rehashes 300 MB. Presence + validated verification marker is sufficient.
        File(active, "model.onnx").writeText("changed")
        val restored = voices(dir); restored.initialize(); assertEquals(active, restored.activeFileOrNull())
    }
    @Test fun `zip slip rejected and failed voice switch preserves active marker`() = runBlocking {
        val dir = temporary.newFolder("voices"); val store = voices(dir)
        val good = zip("model.onnx" to "onnx", "tokens.txt" to "tokens")
        voiceCatalog(good); store.refreshCatalog(); bytes(good); store.downloadSelected()
        val active = store.activeFileOrNull(); val marker = File(dir, "active-voice.json").readText()
        val malicious = zip("../escape" to "bad", "tokens.txt" to "tokens")
        val descriptor = VoiceDescriptor("voice", "Voice", "2", malicious.size.toLong(), sha256(malicious))
        server.enqueue(MockResponse().setBody(buildJsonObject { put("voices", Json.encodeToJsonElement(listOf(descriptor))) }.toString()))
        store.refreshCatalog(); bytes(malicious); store.downloadSelected()
        assertTrue(store.download.value is ArtifactDownloadState.Failed)
        assertEquals(active, store.activeFileOrNull()); assertEquals(marker, File(dir, "active-voice.json").readText())
        assertFalse(File(dir, "escape").exists()); assertFalse(File(dir, ".voice.2.tmp").exists())
    }
    @Test fun `legacy default voice migrates into catalog layout`() = runBlocking {
        val dir = temporary.newFolder("voices"); File(dir, "default").mkdirs()
        File(dir, "default/model.onnx").writeText("model"); File(dir, "default/tokens.txt").writeText("tokens")
        File(dir, "active-version").writeText("default")
        val store = voices(dir); store.initialize()
        assertEquals(File(dir, "default/default"), store.activeFileOrNull())
        assertFalse(File(dir, "active-version").exists())
        assertTrue(completeVoice(File(dir, "default/default")))
    }
    @Test fun `deleting active artifact clears marker and download state`() = runBlocking {
        catalog(model()); models.refreshCatalog(); bytes("model".toByteArray()); models.downloadSelected()
        assertTrue(models.delete(models.downloaded.value.single()))
        assertNull(models.activeFileOrNull()); assertFalse(File(root, "active-model").exists())
        assertEquals(ArtifactDownloadState.NotDownloaded, models.download.value)
    }
    @Test fun `auth failure uses exact user facing error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403)); models.refreshCatalog()
        assertEquals("Sync server rejected the auth token. Check the Sync Auth Token setting.", models.catalog.value.error)
    }
}
