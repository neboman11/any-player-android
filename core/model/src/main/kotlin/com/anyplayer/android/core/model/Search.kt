package com.anyplayer.android.core.model

enum class SearchType { TRACKS, PLAYLISTS }

data class SearchResult(
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)
