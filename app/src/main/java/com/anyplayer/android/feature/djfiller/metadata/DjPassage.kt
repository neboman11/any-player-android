package com.anyplayer.android.feature.djfiller.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One retrieved chunk of source text, as returned by the sync server's `/v1/dj-passages`. */
@Serializable
data class DjPassage(
    @SerialName("chunk_id") val chunkId: Long,
    val text: String,
    val source: String,
    @SerialName("source_url") val sourceUrl: String,
    val title: String = "",
    val subject: String = "",
    val lang: String = ""
)

/** The passages placed in one break's prompt; their chunk ids are what gets marked played. */
@Serializable
data class DjPassages(val passages: List<DjPassage>) {
    val chunkIds: List<Long> get() = passages.map { it.chunkId }
}

const val PASSAGE_TOKEN_BUDGET = 2500

/** Conservative token estimate for the on-device model: ~1 token per 3 Latin characters
 *  plus one per non-Latin character (CJK text has no spaces and tokenizes near per-character). */
fun estimateTokens(text: String): Int {
    val nonLatin = text.count { it.code > 0x2FF && !it.isWhitespace() }
    val latin = text.count { it.code <= 0x2FF && !it.isWhitespace() }
    return (latin + 2) / 3 + nonLatin
}

/** Keeps passages in the server's rank order until [budget] would be exceeded; always keeps the first. */
fun fitToBudget(passages: List<DjPassage>, budget: Int = PASSAGE_TOKEN_BUDGET): List<DjPassage> {
    val kept = mutableListOf<DjPassage>()
    var used = 0
    for (passage in passages) {
        val cost = estimateTokens(passage.text)
        if (kept.isNotEmpty() && used + cost > budget) break
        kept += passage
        used += cost
    }
    return kept
}
