package com.anyplayer.android.core.model

import kotlinx.serialization.Serializable

/**
 * Result of running deduplication over a [PlaylistTrack] list.
 *
 * Mirrors Rust [DeduplicateResult] and TypeScript [DeduplicateResult].
 */
@Serializable
data class DeduplicateResult(
    /** Tracks to keep: first occurrence of each unique title+artist key, in original order. */
    val tracks: List<PlaylistTrack>,
    /** Groups of duplicates found. Empty when no duplicates exist. */
    val duplicateGroups: List<DuplicateGroup>
)
