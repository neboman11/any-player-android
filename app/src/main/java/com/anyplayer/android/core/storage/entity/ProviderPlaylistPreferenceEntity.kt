package com.anyplayer.android.core.storage.entity

import androidx.room.Entity

/**
 * Stores app-owned metadata about a provider playlist, keyed by (source, playlistId).
 * This is separate from provider playlist objects themselves — only preference flags
 * are stored here, never provider playlist content.
 *
 * SAFE-01: No provider playlist data is mutated. This table holds only mode metadata.
 */
@Entity(
    tableName = "provider_playlist_preferences",
    primaryKeys = ["source", "playlistId"]
)
data class ProviderPlaylistPreferenceEntity(
    /** Provider source identifier (e.g. "spotify", "jellyfin", "plex"). */
    val source: String,
    /** Provider-scoped playlist identifier. */
    val playlistId: String,
    /** Whether deduplication is active for this provider playlist. */
    val isDistinct: Boolean
)
