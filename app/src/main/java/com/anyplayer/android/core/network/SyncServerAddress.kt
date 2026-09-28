package com.anyplayer.android.core.network

/** Shared by every client that talks to the user's own sync server (any-player-sync-server):
 *  [com.anyplayer.android.feature.djfiller.DjModelManager]/[com.anyplayer.android.feature.djfiller.VoiceModelDownloader]
 *  and [com.anyplayer.android.feature.sync.SyncSnapshotClient] each independently duplicated
 *  this normalization before it was hoisted here. */

internal fun normalizeSyncServerBaseUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    return when {
        trimmed.isBlank() -> trimmed
        trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
        else -> "https://$trimmed"
    }
}

internal fun normalizeSyncServerAuthToken(raw: String): String =
    raw.trim().replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "")
