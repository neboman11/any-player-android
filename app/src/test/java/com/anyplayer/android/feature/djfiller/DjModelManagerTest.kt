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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class DjModelManagerTest {
    @Test
    fun `existing version file with mismatched server sha is replaced before ready`() = runTest {
        val filesDir = Files.createTempDirectory("dj-model-manager-test").toFile()
        val modelDir = File(filesDir, "dj_models").apply { mkdirs() }
        val existing = File(modelDir, "v1.task").apply { writeText("stale") }
        val downloaded = "fresh model".toByteArray()
        val requests = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)

        try {
            val manager = DjModelManager(
                context(filesDir),
                modelClient(requests, downloaded),
                Json,
                preferences(),
                ioDispatcher = dispatcher
            )

            manager.startDownload()
            advanceUntilIdle()

            assertEquals(listOf("/v1/dj-model/info", "/v1/dj-model/download"), requests)
            assertEquals("fresh model", existing.readText())
            assertTrue(manager.downloadState.value is DjModelDownloadState.Ready)
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

    private fun modelClient(requests: MutableList<String>, model: ByteArray): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.encodedPath
            val body = if (request.url.encodedPath.endsWith("/info")) {
                """{"version":"v1","size_bytes":${model.size},"sha256":"${sha256(model)}"}"""
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
