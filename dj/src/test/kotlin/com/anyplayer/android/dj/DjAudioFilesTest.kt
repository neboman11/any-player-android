package com.anyplayer.android.dj

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DjAudioFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file(name: String) = temporary.newFile(name).apply { writeText("wav") }

    @Test fun `started delivery survives drop and cache clear until ended`() {
        val files = DjAudioFiles()
        val key = AudioKey("song", listOf(1))
        val cache = file("cached.wav")
        val delivery = file("delivery.wav")
        files.remember(key, CachedAudio(cache))
        files.deliver("break", key, delivery)
        files.started("break")
        assertFalse(cache.exists())
        assertNull(files.cached(key))
        assertFalse(files.dropped("break"))
        files.clearFinished()
        assertTrue(delivery.exists())
        files.ended("break")
        assertFalse(delivery.exists())
    }

    @Test fun `never started delivery is removed on drop without losing reusable cache`() {
        val files = DjAudioFiles()
        val key = AudioKey("song", listOf(1))
        val cache = file("cached.wav")
        val delivery = file("delivery.wav")
        files.remember(key, CachedAudio(cache))
        files.deliver("break", key, delivery)
        assertTrue(files.dropped("break"))
        assertFalse(delivery.exists())
        assertEquals(cache, files.cached(key)?.file)
        assertTrue(cache.exists())
        files.ended("break") // late/duplicate events are harmless
    }

    @Test fun `finished audio stays bounded while a target flip can reuse recent output`() {
        val files = DjAudioFiles()
        val outputs = (1..4).map { file("$it.wav") }
        outputs.forEachIndexed { i, file -> files.remember(AudioKey("$i", listOf(i.toLong())), CachedAudio(file)) }
        assertFalse(outputs.first().exists())
        assertTrue(outputs.drop(1).all(File::exists))
        files.clearFinished()
        assertTrue(outputs.none(File::exists))
    }
}
