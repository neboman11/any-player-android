package com.anyplayer.android.core.model

private val SPOTIFY_TRACK_ID = Regex("[A-Za-z0-9]{22}")

fun normalizeSpotifyTrackId(value: String): String = value.removePrefix("spotify:track:").trim()

fun trackIdsMatch(left: String, right: String): Boolean =
    left == right || normalizeSpotifyTrackId(left) == normalizeSpotifyTrackId(right)

/** True when [value] normalizes to a base-62 Spotify track id (22 chars). */
fun isValidSpotifyTrackId(value: String): Boolean = SPOTIFY_TRACK_ID.matches(normalizeSpotifyTrackId(value))
