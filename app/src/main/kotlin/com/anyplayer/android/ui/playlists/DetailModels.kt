package com.anyplayer.android.ui.playlists

import androidx.compose.runtime.Immutable
import com.anyplayer.android.core.model.Dedupe
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.ui.components.TrackRowUi
import com.anyplayer.android.ui.components.TrackTables

/** One duplicate group: the kept (first) occurrence and the later ones, by original index. */
@Immutable
data class DuplicateGroupUi(val key: String, val kept: TrackRowUi, val duplicates: List<TrackRowUi>)

object DuplicateGroups {
    fun build(tracks: List<Track>): List<DuplicateGroupUi> =
        Dedupe.deduplicateTracks(tracks).duplicateGroups.mapNotNull { group ->
            val kept = tracks.getOrNull(group.firstOccurrenceIndex) ?: return@mapNotNull null
            DuplicateGroupUi(
                key = group.key,
                kept = TrackTables.rowFor(group.firstOccurrenceIndex, kept),
                duplicates = group.occurrences.mapNotNull { occurrence ->
                    tracks.getOrNull(occurrence.index)?.let { TrackTables.rowFor(occurrence.index, it) }
                },
            )
        }
}
