package com.anyplayer.android.core.model

fun normalizePlaylistId(sourceType: SourceType, playlistId: String): String {
    val trimmed = playlistId.trim()
    if (sourceType != SourceType.SPOTIFY || trimmed.isBlank()) return trimmed
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
    return trimmed.substringBefore('?').substringBefore('/')
}
