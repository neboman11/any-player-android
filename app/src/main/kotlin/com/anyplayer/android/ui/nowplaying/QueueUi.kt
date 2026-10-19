package com.anyplayer.android.ui.nowplaying

import androidx.compose.runtime.Immutable
import com.anyplayer.android.core.model.QueueEntry
import com.anyplayer.android.core.model.QueueState
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.dj.PreparationStatus
import com.anyplayer.android.ui.components.formatDuration
import com.anyplayer.android.ui.components.qualityLabel

/** One "Up Next"/history row (subtitle: artist • quality). [uid] is null for the DJ entry; [key] is unique within the queue. */
@Immutable
data class QueueRowUi(
    val key: String,
    val uid: Long?,
    val title: String,
    val subtitle: String,
    val source: SourceType?,
    val durationText: String,
    val imageUrl: String?,
    val djStatus: PreparationStatus? = null,
) {
    val isDj: Boolean get() = uid == null
}

@Immutable
data class QueueUi(
    val upcoming: List<QueueRowUi> = emptyList(),
    val history: List<QueueRowUi> = emptyList(),
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean get() = !loaded
}

internal const val DJ_TITLE = "AnyPlayer DJ"
internal const val DJ_ROW_KEY = "dj_break"

object QueueUiBuilder {
    /** Play-order rows after the cursor (with the DJ entry inserted) and before it. */
    fun build(queue: QueueState, hint: DjQueueHint): QueueUi {
        val upcomingStart = (queue.cursor + 1).coerceAtLeast(0)
        val upcoming = ArrayList<QueueRowUi>((queue.order.size - upcomingStart).coerceAtLeast(0) + 1)
        for (position in upcomingStart until queue.order.size) upcoming += row(queue.entryAt(position))
        val history = ArrayList<QueueRowUi>(queue.cursor.coerceAtLeast(0))
        for (position in 0 until queue.cursor) history += row(queue.entryAt(position))
        val songsAway = hint.songsAway
        if (queue.entries.isNotEmpty() && hint.showEntries && songsAway != null) {
            upcoming.add(songsAway.coerceIn(0, upcoming.size), djRow(hint.preparationStatus))
        }
        return QueueUi(upcoming = upcoming, history = history, loaded = queue.entries.isNotEmpty())
    }

    private fun row(entry: QueueEntry): QueueRowUi {
        val track = entry.track
        return QueueRowUi(
            key = "uid_${entry.uid}",
            uid = entry.uid,
            title = track.title,
            subtitle = listOfNotNull(track.artist.ifBlank { "—" }, qualityLabel(track)).joinToString(" • "),
            source = track.source,
            durationText = formatDuration(track.durationMs),
            imageUrl = track.imageUrl,
        )
    }

    private fun djRow(status: PreparationStatus?) = QueueRowUi(
        key = DJ_ROW_KEY,
        uid = null,
        title = DJ_TITLE,
        subtitle = when (status) {
            PreparationStatus.NOT_STARTED, null -> "Not started"
            PreparationStatus.PROCESSING -> "Processing"
            PreparationStatus.READY -> "Ready for playback"
        },
        source = null,
        durationText = "—",
        imageUrl = null,
        djStatus = status ?: PreparationStatus.NOT_STARTED,
    )
}
