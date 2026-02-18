package com.anyplayer.android.core.model

import kotlinx.serialization.Serializable

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
    val url: String? = null
)
