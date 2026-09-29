package com.anyplayer.android.feature.djfiller.metadata

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjPassageTest {
    private fun passage(id: Long, text: String) = DjPassage(id, text, "wikipedia-en", "https://en.wikipedia.org/wiki/X")

    @Test fun `decodes the server response shape`() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(DjPassage.serializer(),
            """{"chunk_id":9,"text":"t","source":"genius","source_url":"u","title":"T","subject":"song","lang":"en"}""")
        assertEquals(9L, decoded.chunkId)
        assertEquals("u", decoded.sourceUrl)
    }

    @Test fun `estimates latin by words and cjk by characters`() {
        assertEquals(4, estimateTokens("one two three"))
        assertTrue(estimateTokens("日本のロックバンド") >= 9)
    }

    @Test fun `fit keeps rank order within budget and always at least one`() {
        assertEquals(listOf(1L), fitToBudget(listOf(passage(1, "word ".repeat(3000)), passage(2, "short one")), 2500).map { it.chunkId })
        val kept = fitToBudget(listOf(passage(1, "word ".repeat(1500)), passage(2, "word ".repeat(300)), passage(3, "word ".repeat(100))), 2500)
        assertEquals(listOf(1L, 2L), kept.map { it.chunkId })
        assertEquals(emptyList<DjPassage>(), fitToBudget(emptyList(), 2500))
    }

    @Test fun `NBSP-joined text is not under-counted`() {
        // 300 repetitions of "word " (word + a real non-breaking space) = 300 * 4 = 1200
        // Latin non-whitespace chars; ceil(1200 / 3) = 400 tokens, well above the required 300.
        assertTrue(estimateTokens("word ".repeat(300)) >= 300)
    }

    @Test fun `single unbroken Latin token is estimated conservatively`() {
        // A 3000-character unbroken token: 3000 / 3 = 1000 tokens
        assertTrue(estimateTokens("a".repeat(3000)) >= 1000)
    }

    @Test fun `CJK glued to Latin counts every character`() {
        // "KANA-BOON" = 9 Latin chars → ceil(9/3) = 3 tokens
        // "の楽曲シルエット" = 8 CJK chars → 8 tokens
        // Total: 11 tokens
        assertEquals(11, estimateTokens("KANA-BOONの楽曲シルエット"))
    }
}
