package com.anyplayer.android.feature.playback

fun normalizeSpotifyTrackId(value: String): String =
    value.removePrefix("spotify:track:").trim()

fun trackIdsMatch(left: String, right: String): Boolean {
    if (left == right) return true
    return normalizeSpotifyTrackId(left) == normalizeSpotifyTrackId(right)
}
