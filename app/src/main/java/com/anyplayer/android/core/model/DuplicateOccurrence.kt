package com.anyplayer.android.core.model

import kotlinx.serialization.Serializable

/**
 * A single duplicate occurrence of a track within the original input list.
 *
 * Mirrors Rust [DuplicateOccurrence] and TypeScript [DuplicateOccurrence].
 */
@Serializable
data class DuplicateOccurrence(
    /** Zero-based index of this track in the original input list. */
    val index: Int,
    /** Track ID of this occurrence. */
    val trackId: String
)
