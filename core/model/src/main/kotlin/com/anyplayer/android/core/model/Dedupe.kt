package com.anyplayer.android.core.model

import java.util.Locale
import kotlinx.serialization.Serializable

/** A single duplicate occurrence of a track within the original input list. */
@Serializable
data class DuplicateOccurrence(
    /** Zero-based index of this track in the original input list. */
    val index: Int,
    val trackId: String,
)

/** Tracks sharing one dedupe key; [occurrences] excludes the first (kept) occurrence. */
@Serializable
data class DuplicateGroup(
    /** "{lowercase_trimmed_title}|{lowercase_trimmed_artist}". */
    val key: String,
    val firstOccurrenceIndex: Int,
    val occurrences: List<DuplicateOccurrence>,
)

@Serializable
data class DeduplicateResult(
    val tracks: List<PlaylistTrack>,
    val duplicateGroups: List<DuplicateGroup>,
)

@Serializable
data class DeduplicateTracksResult(
    val tracks: List<Track>,
    val duplicateGroups: List<DuplicateGroup>,
)

/**
 * Title|artist dedupe shared with the Rust and TypeScript clients (`dedup_spec.json`):
 * first occurrence kept, later ones reported per group in input order.
 */
object Dedupe {
    fun key(title: String, artist: String): String =
        "${title.trim().lowercase(Locale.ROOT)}|${artist.trim().lowercase(Locale.ROOT)}"

    fun deduplicate(tracks: List<PlaylistTrack>): DeduplicateResult {
        val (kept, groups) = dedupe(tracks, { key(it.title, it.artist) }, { it.trackId })
        return DeduplicateResult(kept, groups)
    }

    fun deduplicateTracks(tracks: List<Track>): DeduplicateTracksResult {
        val (kept, groups) = dedupe(tracks, { key(it.title, it.artist) }, { it.id })
        return DeduplicateTracksResult(kept, groups)
    }

    private inline fun <T> dedupe(
        items: List<T>,
        keyOf: (T) -> String,
        idOf: (T) -> String,
    ): Pair<List<T>, List<DuplicateGroup>> {
        // LinkedHashMap keeps groups ordered by first occurrence.
        val firstIndex = LinkedHashMap<String, Int>()
        val duplicates = HashMap<String, MutableList<DuplicateOccurrence>>()
        val kept = ArrayList<T>(items.size)
        items.forEachIndexed { index, item ->
            val key = keyOf(item)
            if (firstIndex.putIfAbsent(key, index) == null) {
                kept.add(item)
            } else {
                duplicates.getOrPut(key) { mutableListOf() }.add(DuplicateOccurrence(index, idOf(item)))
            }
        }
        val groups = firstIndex.mapNotNull { (key, first) ->
            duplicates[key]?.let { DuplicateGroup(key, first, it) }
        }
        return kept to groups
    }
}
