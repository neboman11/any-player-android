package com.anyplayer.android.core.model

import kotlinx.serialization.Serializable

/**
 * A group of tracks that share the same normalized dedup key.
 *
 * The first occurrence is kept in [DeduplicateResult.tracks]; all subsequent
 * occurrences are recorded in [occurrences].
 *
 * Mirrors Rust [DuplicateGroup] and TypeScript [DuplicateGroup].
 */
@Serializable
data class DuplicateGroup(
    /** Normalized dedup key: "{lowercase_trimmed_title}|{lowercase_trimmed_artist}". */
    val key: String,
    /** Zero-based index of the first (kept) occurrence in the original input. */
    val firstOccurrenceIndex: Int,
    /** All duplicate occurrences (does NOT include the first occurrence). */
    val occurrences: List<DuplicateOccurrence>
)
