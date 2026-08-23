package com.anyplayer.android.feature.playback.service

import android.net.Uri
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track

internal fun Track.resolvePlaybackArtworkUri(): Uri? {
    val rawArtwork = imageUrl?.trim().orEmpty()
    if (rawArtwork.isEmpty()) {
        return null
    }

    val parsedArtwork = Uri.parse(rawArtwork)
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

    return if (source == SourceType.PLEX) {
        candidate.withPlexTokenIfMissing(plexStreamInfo?.token)
    } else {
        candidate
    }
}

private data class PlexStreamInfo(
    val baseUrl: String,
    val token: String?
)

private fun Track.resolvePlexStreamInfo(): PlexStreamInfo? {
    val streamUrl = url?.trim().orEmpty()
    if (streamUrl.isEmpty()) return null

    val parsedStream = Uri.parse(streamUrl)
    val scheme = parsedStream.scheme?.takeIf { it.isNotBlank() } ?: return null
    val authority = parsedStream.authority?.takeIf { it.isNotBlank() } ?: return null
    val token = parsedStream.getQueryParameter("X-Plex-Token")
        ?: parsedStream.getQueryParameter("x-plex-token")

    // Preserve any reverse-proxy path prefix (e.g. "/plex") before Plex's "/library/..." root.
    val fullPath = parsedStream.path.orEmpty()
    val libraryIdx = fullPath.indexOf("/library", ignoreCase = true)
    val basePath = if (libraryIdx >= 0) fullPath.substring(0, libraryIdx) else ""
    val baseUrl = "$scheme://$authority$basePath"
    return PlexStreamInfo(baseUrl = baseUrl, token = token)
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
