package com.anyplayer.android.playback

import android.net.Uri
import android.util.Log
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track

private const val TAG = "PlaybackArtworkUri"

internal fun Track.resolvePlaybackArtworkUri(): Uri? {
    val rawArtwork = imageUrl?.trim().orEmpty()
    if (rawArtwork.isEmpty()) {
        return null
    }

    val parsedArtwork = runCatching { Uri.parse(rawArtwork) }.getOrNull() ?: return null
    val plexStreamInfo = if (source == SourceType.PLEX) resolvePlexStreamInfo() else null
    val candidate = when {
        !parsedArtwork.scheme.isNullOrBlank() -> parsedArtwork
        source == SourceType.PLEX && plexStreamInfo != null -> {
            val relativePath = rawArtwork.trimStart('/')
            if (relativePath.isBlank()) {
                parsedArtwork
            } else {
                Uri.parse("${plexStreamInfo.baseUrl}/$relativePath")
            }
        }
        else -> parsedArtwork
    }

    val result = if (source == SourceType.PLEX) {
        candidate.withPlexTokenIfMissing(plexStreamInfo?.token)
    } else {
        candidate
    }
    
    // Log artwork URI resolution for debugging
    if (source == SourceType.PLEX && result != null) {
        Log.d(TAG, "Resolved Plex artwork URI for track: $title - has token: ${result.getQueryParameter("X-Plex-Token") != null}")
    }
    
    return result
}

private data class PlexStreamInfo(
    val baseUrl: String,
    val token: String?
)

private fun Track.resolvePlexStreamInfo(): PlexStreamInfo? {
    val streamUrl = url?.trim().orEmpty()
    if (streamUrl.isEmpty()) return null

    val parsedStream = runCatching { Uri.parse(streamUrl) }.getOrNull() ?: return null
    val scheme = parsedStream.scheme?.takeIf { it.isNotBlank() } ?: return null
    val authority = parsedStream.authority?.takeIf { it.isNotBlank() } ?: return null
    val token = parsedStream.getQueryParameter("X-Plex-Token")
        ?: parsedStream.getQueryParameter("x-plex-token")
    return PlexStreamInfo(baseUrl = "$scheme://$authority", token = token)
}

private fun Uri.withPlexTokenIfMissing(token: String?): Uri {
    if (token.isNullOrBlank()) return this
    val existingToken = getQueryParameter("X-Plex-Token")
        ?: getQueryParameter("x-plex-token")
    if (!existingToken.isNullOrBlank()) return this
    return buildUpon()
        .appendQueryParameter("X-Plex-Token", token)
        .build()
}
