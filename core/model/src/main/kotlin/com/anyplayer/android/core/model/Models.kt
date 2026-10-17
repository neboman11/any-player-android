package com.anyplayer.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val source: SourceType,
    val url: String? = null,
    val imageUrl: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val enriched: Boolean? = null,
    /** True for the synthetic AI DJ voice-over track. Never persisted as a real song. */
    val isDjFiller: Boolean = false,
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val owner: String,
    val trackCount: Int,
    val source: SourceType,
    val imageUrl: String? = null,
    val tracks: List<Track>? = null,
    val description: String? = null,
)

@Serializable
data class CustomPlaylist(
    val id: String,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val trackCount: Int,
    val playlistType: PlaylistType,
    val isDistinct: Boolean = false,
)

@Serializable
data class PlaylistTrack(
    val id: String,
    val playlistId: String,
    val trackSource: SourceType,
    val trackId: String,
    val position: Int,
    val addedAt: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val imageUrl: String? = null,
    val url: String? = null,
)

@Serializable
data class UnionPlaylistSource(
    val id: String,
    val unionPlaylistId: String,
    val sourceType: SourceType,
    val sourcePlaylistId: String,
    val position: Int,
    val addedAt: String,
)

/** Kept only for the sync/export wire format (legacy shape). */
@Serializable
data class PlaybackStatus(
    val state: PlaybackStateType,
    val shuffle: Boolean,
    val repeatMode: RepeatMode,
    val volume: Int,
    val currentTrack: Track? = null,
    val position: Long,
    val duration: Long,
    val queue: List<Track>,
    /** Tracks in actual playback order — equals [queue] when shuffle is off. */
    val orderedQueue: List<Track> = emptyList(),
    val errorMessage: String? = null,
)

/** Export wire format only; there is no UI for it in v2. */
@Serializable
data class ColumnPreferences(
    val key: String,
    val visible: Boolean,
    val position: Int,
)

@Serializable
data class ProviderConnectionProfile(
    val source: SourceType,
    val connected: Boolean,
    val serverUrl: String? = null,
    val username: String? = null,
    val hasToken: Boolean = false,
    val isPremium: Boolean? = null,
    val playbackReady: Boolean? = null,
    val lastError: String? = null,
)
