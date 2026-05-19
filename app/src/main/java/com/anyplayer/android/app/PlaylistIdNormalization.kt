package com.anyplayer.android.app

import com.anyplayer.android.core.model.SourceType

internal fun normalizeUnionSourcePlaylistId(sourceType: SourceType, sourcePlaylistId: String): String {
    if (sourceType != SourceType.SPOTIFY) return sourcePlaylistId.trim()
    return sourcePlaylistId
        .trim()
        .substringAfter("spotify:playlist:", sourcePlaylistId.trim())
        .let { value ->
            value.substringAfter("/playlist/", value)
                .substringBefore('?')
                .substringBefore('/')
        }
}
