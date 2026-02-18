package com.anyplayer.android.feature.startup

import com.anyplayer.android.core.model.Playlist

interface StartupCatalogGateway {
    suspend fun getCachedProviderPlaylists(): List<Playlist>
    suspend fun getAllProviderPlaylistsWithCache(offset: Int = 0, limit: Int = 100): List<Playlist>
}
