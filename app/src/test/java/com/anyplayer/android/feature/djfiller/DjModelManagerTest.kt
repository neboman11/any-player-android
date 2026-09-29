package com.anyplayer.android.feature.djfiller

import android.content.Context
import com.anyplayer.android.feature.djfiller.model.DjModelDownloadState
import com.anyplayer.android.feature.sync.SyncPreferences
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class DjModelManagerTest {
    @Test
    fun `selected catalog model downloads into its own directory and becomes active`() = runTest {
        val filesDir = Files.createTempDirectory("dj-model-manager-test").toFile()
        val legacy = File(filesDir, "dj_models/v0.task").apply { parentFile.mkdirs(); writeText("legacy") }
        val downloaded = "fresh model".toByteArray()
        val requests = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)

        try {
            val prefs = preferences()
            val manager = DjModelManager(context(filesDir), modelClient(requests, downloaded), Json, prefs, dispatcher)
            assertEquals(legacy, manager.modelFileOrNull())

            manager.refreshCatalog()
            advanceUntilIdle()
            manager.selectModel("gemma-4-e2b")
            verify(prefs).setSelectedDjModelId("gemma-4-e2b")
            whenever(prefs.selectedDjModelId()).thenReturn("gemma-4-e2b")
            manager.startDownload()
            advanceUntilIdle()

            assertEquals(listOf("/v1/dj-models", "/v1/dj-models/gemma-4-e2b/download"), requests)
            val active = File(filesDir, "dj_models/gemma-4-e2b/v1.litertlm")
            assertEquals("fresh model", active.readText())
            assertEquals(active, manager.modelFileOrNull())
            assertEquals("gemma-4-e2b", manager.modelState.value.activeId)
            assertFalse(legacy.exists())

            val restored = DjModelManager(context(filesDir), modelClient(requests, downloaded), Json, prefs, dispatcher)
            assertEquals(active, restored.modelFileOrNull())
            assertEquals("gemma-4-e2b", restored.modelState.value.activeId)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `failed switch keeps the previously active model`() = runTest {
        val filesDir = Files.createTempDirectory("dj-model-manager-test").toFile()
        val legacy = File(filesDir, "dj_models/v0.task").apply { parentFile.mkdirs(); writeText("legacy") }
        val dispatcher = StandardTestDispatcher(testScheduler)

        try {
            val prefs = preferences()
            whenever(prefs.selectedDjModelId()).thenReturn("gemma-4-e2b")
            // Catalog advertises the sha256 of different bytes than the download returns.
            val manager = DjModelManager(
                context(filesDir),
                modelClient(mutableListOf(), "served".toByteArray(), advertised = "expected".toByteArray()),
                Json,
                prefs,
                dispatcher
            )
            manager.startDownload()
            advanceUntilIdle()

            assertTrue(manager.downloadState.value is DjModelDownloadState.Failed)
            assertEquals(legacy, manager.modelFileOrNull())
            assertTrue(legacy.exists())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private fun context(filesDir: File): Context = mock<Context>().also {
        whenever(it.filesDir).thenReturn(filesDir)
    }

    private fun preferences(): SyncPreferencesStore = mock<SyncPreferencesStore>().also {
        whenever(it.read()).thenReturn(SyncPreferences("https://sync.example", "token"))
    }

    private fun modelClient(
        requests: MutableList<String>,
        model: ByteArray,
        advertised: ByteArray = model
    ): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.encodedPath
            val body = if (request.url.encodedPath == "/v1/dj-models") {
                ("""{"default_id":"gemma-4-e2b","models":[{"id":"gemma-4-e2b","name":"Gemma 4 E2B",""" +
                    """"version":"v1","size_bytes":${advertised.size},"sha256":"${sha256(advertised)}","format":"litertlm"}]}""")
                    .toResponseBody("application/json".toMediaType())
            } else {
                model.toResponseBody("application/octet-stream".toMediaType())
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }.build()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
