package com.anyplayer.android.feature.auth.spotify

import com.anyplayer.android.BuildConfig

/**
 * Active Spotify OAuth client IDs used by Any Player.
 */
object SpotifyClientIds {
    val ACTIVE: String
        get() = BuildConfig.SPOTIFY_CLIENT_ID

    /** Registered with the Spotify Developer Dashboard for the PKCE web-auth flow. */
    const val REDIRECT_URI: String = "anyplayer://spotify-callback"
}
