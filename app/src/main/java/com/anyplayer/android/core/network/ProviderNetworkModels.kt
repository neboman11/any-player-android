package com.anyplayer.android.core.network

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.Track

sealed class ProviderConnectionCheck {
    data class Connected(val username: String? = null, val metadata: Map<String, String> = emptyMap()) : ProviderConnectionCheck()
    data class Failed(val message: String) : ProviderConnectionCheck()
}

data class ProviderSearchResult(
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList()
)
