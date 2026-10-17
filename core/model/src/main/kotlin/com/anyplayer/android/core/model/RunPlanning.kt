package com.anyplayer.android.core.model

import java.util.Locale

enum class BackendKind { EXO, SPOTIFY, UNPLAYABLE }

private val EXO_SCHEMES = setOf("http", "https", "file", "content", "android.resource")

/**
 * SPOTIFY source with a valid 22-char track id → SPOTIFY; an http/https/file/content/
 * android.resource url → EXO; anything else → UNPLAYABLE.
 */
fun backendFor(track: Track): BackendKind {
    if (track.source == SourceType.SPOTIFY) {
        return if (isValidSpotifyTrackId(track.id)) BackendKind.SPOTIFY else BackendKind.UNPLAYABLE
    }
    val scheme = track.url?.trim()?.substringBefore(':', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT)
    return if (scheme != null && scheme in EXO_SCHEMES) BackendKind.EXO else BackendKind.UNPLAYABLE
}

/** A DJ break that must play immediately before the entry [beforeUid]. */
data class PendingBreak(val beforeUid: Long, val breakId: String, val audioUri: String)

sealed interface PlanItem {
    data class Song(val entry: QueueEntry, val orderPos: Int) : PlanItem
    data class Break(val breakId: String, val audioUri: String) : PlanItem
}

/**
 * A play-order slice owned by one backend: [kind] is EXO or SPOTIFY, never UNPLAYABLE.
 * Break items only appear in EXO runs.
 */
data class Run(val kind: BackendKind, val items: List<PlanItem>)

/** The single-item EXO run that plays [pendingBreak] at a SPOTIFY run boundary. */
fun breakRun(pendingBreak: PendingBreak): Run =
    Run(BackendKind.EXO, listOf(PlanItem.Break(pendingBreak.breakId, pendingBreak.audioUri)))

/**
 * Plans the run starting at [fromOrderPos], without wrapping, at most [maxItems] items.
 * UNPLAYABLE entries are skipped (also before the first item) and never end a run; the run's
 * kind is that of its first playable entry and it ends at the first playable entry of another
 * kind. Returns null when no playable entry exists at or after [fromOrderPos].
 *
 * With a [pendingBreak], an EXO run splices the break right before `beforeUid`; a SPOTIFY run
 * stops before `beforeUid` (unless it is the run's first item — the engine plays [breakRun]
 * before starting a run at the break target).
 */
fun planRun(
    state: QueueState,
    fromOrderPos: Int,
    pendingBreak: PendingBreak?,
    maxItems: Int = 50,
): Run? {
    require(maxItems > 0) { "maxItems must be positive" }
    if (fromOrderPos < 0) return null
    var kind: BackendKind? = null
    val items = ArrayList<PlanItem>()
    var pos = fromOrderPos
    while (pos < state.order.size && items.size < maxItems) {
        val entry = state.entryAt(pos)
        val entryKind = backendFor(entry.track)
        if (entryKind == BackendKind.UNPLAYABLE) {
            pos++
            continue
        }
        if (kind == null) kind = entryKind else if (entryKind != kind) break
        if (pendingBreak != null && entry.uid == pendingBreak.beforeUid) {
            if (kind == BackendKind.EXO) {
                items.add(PlanItem.Break(pendingBreak.breakId, pendingBreak.audioUri))
                if (items.size >= maxItems) break
            } else if (items.isNotEmpty()) {
                break
            }
        }
        items.add(PlanItem.Song(entry, pos))
        pos++
    }
    return kind?.let { Run(it, items) }
}
