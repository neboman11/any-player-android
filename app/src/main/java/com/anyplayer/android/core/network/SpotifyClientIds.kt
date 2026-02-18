package com.anyplayer.android.core.network

import com.anyplayer.android.BuildConfig

/**
 * Active Spotify OAuth client IDs used by Any Player.
 */
object SpotifyClientIds {
    val ACTIVE: String
        get() = BuildConfig.SPOTIFY_CLIENT_ID
}
