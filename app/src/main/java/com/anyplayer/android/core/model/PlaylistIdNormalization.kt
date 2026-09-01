package com.anyplayer.android.core.model

fun normalizePlaylistId(sourceType: SourceType, playlistId: String): String {
    if (sourceType != SourceType.SPOTIFY) return playlistId.trim()
    val trimmed = playlistId.trim()
    if (trimmed.isBlank()) return trimmed

    val uriPrefix = "spotify:playlist:"
    if (trimmed.startsWith(uriPrefix, ignoreCase = true)) {
        return trimmed.substringAfterLast(':').substringBefore('?').substringBefore('/')
    }

    val marker = "/playlist/"
    val markerIndex = trimmed.indexOf(marker)
    if (markerIndex >= 0) {
        return trimmed
            .substring(markerIndex + marker.length)
            .substringBefore('?')
            .substringBefore('/')
    }

    return trimmed
}
