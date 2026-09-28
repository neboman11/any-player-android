package com.anyplayer.android.feature.djfiller

import android.content.Context
import com.anyplayer.android.feature.djfiller.metadata.DjPassages
import com.anyplayer.android.feature.djfiller.metadata.DjPassage
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DjFillerAudioCacheTest {
    @Test fun `saving a restored audio file preserves it`() {
        val context = RuntimeEnvironment.getApplication() as Context
        val cache = DjFillerAudioCache(context)
        cache.clear()
        val fact = DjPassages(listOf(DjPassage(1, "Song story.", "wikipedia-en", "https://en.wikipedia.org/wiki/Song")))
        val audio = Files.createTempFile("dj-cache", ".wav").toFile()
        try {
            audio.writeText("audio")
            val ready = cache.save("track", audio, fact)
            cache.save("track", ready, fact)
            assertEquals("audio", cache.load("track")?.readText())
            assertEquals(fact, cache.loadPassages("track"))
        } finally { cache.clear(); audio.delete() }
    }

    @Test fun `cached audio keeps its selected fact across cache recreation`() {
        val context = RuntimeEnvironment.getApplication() as Context
        val cache = DjFillerAudioCache(context)
        cache.clear()
        val fact = DjPassages(listOf(DjPassage(1, "Song story.", "wikipedia-en", "https://en.wikipedia.org/wiki/Song")))
        val audio = Files.createTempFile("dj-cache", ".wav").toFile()
        try {
            cache.save("track", audio, fact)
            val restored = DjFillerAudioCache(context)
            assertEquals(fact, restored.loadPassages("track"))
        } finally { cache.clear(); audio.delete() }
    }
}
