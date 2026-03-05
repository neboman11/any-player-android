package com.anyplayer.android.core.model

import kotlinx.serialization.Serializable

/**
 * Result of running deduplication over a [Track] list (used for union and provider queue inputs).
 *
 * Mirrors [DeduplicateResult] but operates on [Track] rather than [PlaylistTrack],
 * since union and provider queue materialization produces [Track] objects.
 *
 * Key contract is identical: `"{lowercase_trimmed_title}|{lowercase_trimmed_artist}"`
 */
@Serializable
data class DeduplicateTracksResult(
    /** Tracks to keep: first occurrence of each unique title+artist key, in original order. */
    val tracks: List<Track>,
    /** Groups of duplicates found. Empty when no duplicates exist. */
    val duplicateGroups: List<DuplicateGroup>
)
