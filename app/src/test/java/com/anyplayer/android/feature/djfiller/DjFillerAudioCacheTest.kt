package com.anyplayer.android.feature.djfiller

import android.content.Context
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DjFillerAudioCacheTest {
    @Test
    fun `generated audio survives cache recreation`() {
        val cacheDir = Files.createTempDirectory("dj-filler-cache").toFile()
        val filesDir = Files.createTempDirectory("dj-filler-files").toFile()

        try {
            val cache = DjFillerAudioCache(context(cacheDir, filesDir))
            val generated = cache.newOutputFile().apply { writeBytes(byteArrayOf(1)) }

            cache.save("next-track", generated)
            val restored = DjFillerAudioCache(context(cacheDir, filesDir)).load("next-track")

            assertEquals("ready.wav", restored?.name)
            assertTrue(restored?.exists() == true)
        } finally {
            cacheDir.deleteRecursively()
            filesDir.deleteRecursively()
        }
    }

    private fun context(cacheDir: java.io.File, filesDir: java.io.File): Context = mock<Context>().also {
        whenever(it.cacheDir).thenReturn(cacheDir)
        whenever(it.filesDir).thenReturn(filesDir)
    }
}
