package com.anyplayer.android.feature.djfiller

import com.anyplayer.android.feature.djfiller.model.DjModelDownloadState
import com.anyplayer.android.feature.sync.SyncPreferences
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceModelDownloaderTest {
    @Test
    fun `catalog request authenticates download route descriptor encoded`() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val bundle = zipBundle("voice.onnx" to "model", "tokens.txt" to "tokens")
        val client = respondingClient(requests, bundle, sha256(bundle))
        val prefs = preferences(selectedId = null)
        val root = Files.createTempDirectory("voice-model-test").toFile()

        try {
            VoiceModelDownloader(root, client, Json, prefs).downloadSelectedVoice()

            assertEquals("/v1/dj-voice-models", requests[0].url.encodedPath)
            assertEquals("/v1/dj-voice-models/voice.name-v1/download", requests[1].url.encodedPath)
            assertTrue(requests.all { it.header("Authorization") == "Bearer token" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `download rejects a response larger than its advertised size`() {
        val output = Files.createTempFile("voice-download", ".zip").toFile()
        try {
            try {
                downloadSyncServerResponseToFile(
                    response = responseWithBody(byteArrayOf(1, 2)),
                    outputFile = output,
                    expectedSize = 1,
                    digest = MessageDigest.getInstance("SHA-256"),
                    onProgress = {}
                )
                fail("expected oversized response to be rejected")
            } catch (_: IllegalStateException) {
                // Expected.
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun `download rejects a response smaller than its advertised size`() {
        val output = Files.createTempFile("voice-download", ".zip").toFile()
        try {
            try {
                downloadSyncServerResponseToFile(
                    response = responseWithBody(byteArrayOf(1)),
                    outputFile = output,
                    expectedSize = 2,
                    digest = MessageDigest.getInstance("SHA-256"),
                    onProgress = {}
                )
                fail("expected truncated response to be rejected")
            } catch (_: IllegalStateException) {
                // Expected.
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun `selection stores only catalog descriptor does not download`() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val client = respondingClient(requests, ByteArray(0), "0".repeat(64))
        val prefs = preferences(selectedId = null)
        val root = Files.createTempDirectory("voice-model-test").toFile()

        try {
            val downloader = VoiceModelDownloader(root, client, Json, prefs)
            val catalog = downloader.refreshCatalog()!!
            downloader.selectVoice(catalog.voices.single())

            assertEquals(1, requests.size)
            verify(prefs).setSelectedDjVoiceId("voice.name-v1")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `selection rejects a descriptor not returned by current catalog`() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val client = respondingClient(requests, ByteArray(0), "0".repeat(64))
        val prefs = preferences(selectedId = null)
        val root = Files.createTempDirectory("voice-model-test").toFile()

        try {
            val downloader = VoiceModelDownloader(root, client, Json, prefs)
            val catalog = downloader.refreshCatalog()!!
            val returned = catalog.voices.single()

            try {
                downloader.selectVoice(returned.copy(name = "forged"))
                fail("expected non-catalog descriptor to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected: selection accepts the actual catalog descriptor only.
            }

            verify(prefs, org.mockito.kotlin.never()).setSelectedDjVoiceId(org.mockito.kotlin.any())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `download URL encodes descriptor id as one route segment`() {
        assertEquals(
            "/v1/dj-voice-models/voice%2Fname/download",
            voiceDownloadUrl("https://sync.example", "voice/name").encodedPath
        )
    }

    @Test
    fun `symlinked tokens never restore as active voice`() {
        val root = Files.createTempDirectory("voice-model-test").toFile()
        val voice = File(root, "voice/v1").apply {
            mkdirs()
            File(this, "voice.onnx").writeText("model")
        }
        val target = File(root, "outside-tokens").apply { writeText("tokens") }
        Files.createSymbolicLink(File(voice, "tokens.txt").toPath(), target.toPath())
        File(root, "active-voice.json").writeText("{\"id\":\"voice\",\"version\":\"v1\"}")

        try {
            assertTrue(downloader(root).downloadState.value is DjModelDownloadState.NotDownloaded)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `symlinked model never restores as active voice`() {
        val root = Files.createTempDirectory("voice-model-test").toFile()
        val voice = File(root, "voice/v1").apply {
            mkdirs()
            File(this, "tokens.txt").writeText("tokens")
        }
        val target = File(root, "outside-model").apply { writeText("model") }
        Files.createSymbolicLink(File(voice, "voice.onnx").toPath(), target.toPath())
        File(root, "active-voice.json").writeText("{\"id\":\"voice\",\"version\":\"v1\"}")

        try {
            assertTrue(downloader(root).downloadState.value is DjModelDownloadState.NotDownloaded)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cache isolates same version from two catalog voices`() {
        val root = Files.createTempDirectory("voice-model-test").toFile()

        try {
            val downloader = downloader(root)
            downloader.activate(descriptor("baritone", "v1"), bundle("baritone"))
            downloader.activate(descriptor("tenor", "v1"), bundle("tenor"))

            assertEquals("tenor", downloader.activeVoice()?.id)
            assertTrue(File(root, "baritone/v1/tokens.txt").isFile)
            assertTrue(File(root, "tenor/v1/tokens.txt").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `onnx without tokens never replaces active voice`() {
        val root = Files.createTempDirectory("voice-model-test").toFile()

        try {
            val downloader = downloader(root)
            downloader.activate(descriptor("baritone", "v1"), bundle("baritone"))

            try {
                downloader.activate(descriptor("tenor", "v1"), onnxOnlyBundle())
                fail("expected incomplete bundle to be rejected")
            } catch (_: IllegalStateException) {
                // Expected: usable voice requires both an ONNX model and tokens.txt.
            }

            assertEquals("baritone", downloader.activeVoice()?.id)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sha mismatch retains prior active voice`() = runTest {
        val root = Files.createTempDirectory("voice-model-test").toFile()
        val bundle = zipBundle("voice.onnx" to "model", "tokens.txt" to "tokens")

        try {
            downloader(root).activate(descriptor("baritone", "v1"), bundle("baritone"))
            val downloader = VoiceModelDownloader(
                root,
                respondingClient(mutableListOf(), bundle, "0".repeat(64)),
                Json,
                preferences(selectedId = "voice.name-v1")
            )

            downloader.downloadSelectedVoice()

            assertEquals("baritone", downloader.activeVoice()?.id)
            assertTrue(downloader.downloadState.value is DjModelDownloadState.Failed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `zip slip entry is rejected before writing outside destination`() {
        val parent = Files.createTempDirectory("voice-zip-test").toFile()
        val destination = File(parent, "destination")
        val archive = zipBundle("../escaped" to "bad", "voice.onnx" to "model", "tokens.txt" to "tokens")

        try {
            try {
                extractZipSafely(archive.inputStream(), destination)
                fail("expected zip slip entry to be rejected")
            } catch (_: IllegalStateException) {
                // Expected: every entry must remain below the extraction root.
            }
            assertFalse(File(parent, "escaped").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `active marker write failure retains prior active voice`() {
        val root = Files.createTempDirectory("voice-model-test").toFile()

        try {
            val downloader = downloader(root)
            downloader.activate(descriptor("baritone", "v1"), bundle("baritone"))
            File(root, ".active-voice.json.tmp").mkdir()

            try {
                downloader.activate(descriptor("tenor", "v1"), bundle("tenor"))
                fail("expected marker write failure")
            } catch (_: IllegalStateException) {
                // Expected: the old marker remains the source of truth.
            }

            assertEquals("baritone", downloader.activeVoice()?.id)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed activation preserves the active voice`() {
        val root = Files.createTempDirectory("voice-model-test").toFile()
        val downloader = VoiceModelDownloader(
            root,
            mock<OkHttpClient>(),
            Json,
            mock<SyncPreferencesStore>()
        )

        try {
            downloader.activate(descriptor("baritone", "v1"), bundle("baritone"))
            try {
                downloader.activate(descriptor("tenor", "v1"), onnxOnlyBundle())
                fail("expected incomplete bundle to be rejected")
            } catch (_: IllegalStateException) {
                // Expected.
            }

            assertEquals("baritone", downloader.activeVoice()?.id)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy default bundle migration is non-blocking and test-drainable`() = runTest {
        val root = Files.createTempDirectory("voice-model-test").toFile()
        File(root, "default").apply {
            mkdirs()
            File(this, "voice.onnx").writeText("model")
            File(this, "tokens.txt").writeText("tokens")
        }
        File(root, "active-version").writeText("default")
        val dispatcher = StandardTestDispatcher(testScheduler)

        try {
            val downloader = downloader(root, dispatcher)

            assertTrue(downloader.downloadState.value is DjModelDownloadState.NotDownloaded)

            advanceUntilIdle()

            assertEquals("default", downloader.activeVoice()?.id)
            assertEquals("default", downloader.activeVoice()?.version)
            assertTrue(File(root, "default/default/voice.onnx").isFile)
            assertEquals(
                "{\"id\":\"default\",\"version\":\"default\"}",
                File(root, "active-voice.json").readText()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `does not restore voice bundle without tokens`() {
        val root = Files.createTempDirectory("voice-model-test").toFile()
        File(root, "voice/v1").apply {
            mkdirs()
            File(this, "voice.onnx").createNewFile()
        }
        File(root, "active-voice.json").writeText("{\"id\":\"voice\",\"version\":\"v1\"}")

        try {
            assertTrue(downloader(root).downloadState.value is DjModelDownloadState.NotDownloaded)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `does not restore another voice when configured version is unsafe`() {
        val root = Files.createTempDirectory("voice-model-test").toFile()
        File(root, "active-version").writeText("../outside-voice")

        try {
            assertTrue(downloader(root).downloadState.value is DjModelDownloadState.NotDownloaded)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun preferences(selectedId: String?): SyncPreferencesStore = mock<SyncPreferencesStore>().also {
        whenever(it.read()).thenReturn(SyncPreferences("https://sync.example", "token"))
        whenever(it.selectedDjVoiceId()).thenReturn(selectedId)
    }

    private fun responseWithBody(body: ByteArray): Response = Response.Builder()
        .request(Request.Builder().url("https://sync.example/v1/dj-voice-models/voice/download").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("application/octet-stream".toMediaType()))
        .build()

    private fun respondingClient(
        requests: MutableList<okhttp3.Request>,
        bundle: ByteArray,
        advertisedSha: String
    ): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val isCatalog = request.url.encodedPath.endsWith("/v1/dj-voice-models")
        val body = if (isCatalog) {
            """{"default_id":"voice.name-v1","voices":[{"id":"voice.name-v1","name":"Voice","version":"v1","size_bytes":${bundle.size},"sha256":"$advertisedSha"}]}"""
                .toResponseBody("application/json".toMediaType())
        } else {
            bundle.toResponseBody("application/zip".toMediaType())
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }.build()

    private fun downloader(
        root: File,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
    ) = VoiceModelDownloader(
        root,
        mock<OkHttpClient>(),
        Json,
        mock<SyncPreferencesStore>(),
        ioDispatcher = ioDispatcher
    )

    private fun descriptor(id: String, version: String) = DjVoiceDescriptor(
        id = id,
        name = id,
        version = version,
        sizeBytes = 1,
        sha256 = "0".repeat(64)
    )

    private fun bundle(name: String): File = Files.createTempDirectory("$name-bundle").toFile().apply {
        File(this, "$name.onnx").writeText("model")
        File(this, "tokens.txt").writeText("tokens")
    }

    private fun onnxOnlyBundle(): File = Files.createTempDirectory("onnx-only-bundle").toFile().apply {
        File(this, "voice.onnx").writeText("model")
    }

    private fun zipBundle(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
