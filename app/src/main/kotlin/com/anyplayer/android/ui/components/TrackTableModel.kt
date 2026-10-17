package com.anyplayer.android.ui.components

import androidx.compose.runtime.Immutable
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import java.util.Locale

enum class TrackSortColumn(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    DURATION("Duration"),
    SOURCE("Source"),
}

/** Header sort: tapping a column cycles ascending → descending → unsorted. */
@Immutable
data class TrackSort(val column: TrackSortColumn? = null, val ascending: Boolean = true) {
    fun cycle(clicked: TrackSortColumn): TrackSort = when {
        column != clicked -> TrackSort(clicked, ascending = true)
        ascending -> TrackSort(clicked, ascending = false)
        else -> TrackSort()
    }
}

/** One table row; [originalIndex] is the position in the unsorted, unfiltered list (the row key). */
@Immutable
data class TrackRowUi(
    val originalIndex: Int,
    val track: Track,
    val title: String,
    val subtitle: String,
    val durationText: String,
    val sourceLabel: String,
)

@Immutable
data class TrackTableUi(
    val sort: TrackSort = TrackSort(),
    val rows: List<TrackRowUi> = emptyList(),
    val totalCount: Int = 0,
)

enum class TrackRowAction(val label: String) {
    PLAY("Play"),
    ADD_TO_QUEUE("Add to Queue"),
    ADD_TO_PLAYLIST("Add to Playlist"),
    REMOVE("Remove"),
}

object TrackTables {
    /** Sorts (stable, by original index on ties) then filters on title/artist/album substrings. */
    fun buildRows(tracks: List<Track>, sort: TrackSort, filter: String): List<TrackRowUi> {
        val indexed = tracks.indices.toMutableList()
        val column = sort.column
        if (column != null) {
            val comparator: Comparator<Int> = when (column) {
                TrackSortColumn.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { tracks[it].title }
                TrackSortColumn.ARTIST -> compareBy(String.CASE_INSENSITIVE_ORDER) { tracks[it].artist }
                TrackSortColumn.ALBUM -> compareBy(String.CASE_INSENSITIVE_ORDER) { tracks[it].album.orEmpty() }
                TrackSortColumn.DURATION -> compareBy { tracks[it].durationMs ?: 0L }
                TrackSortColumn.SOURCE -> compareBy { tracks[it].source.name }
            }
            indexed.sortWith(if (sort.ascending) comparator else comparator.reversed())
        }
        val needle = filter.trim()
        return indexed.mapNotNull { index ->
            val track = tracks[index]
            if (needle.isNotEmpty() &&
                !track.title.contains(needle, ignoreCase = true) &&
                !track.artist.contains(needle, ignoreCase = true) &&
                track.album?.contains(needle, ignoreCase = true) != true
            ) {
                null
            } else {
                rowFor(index, track)
            }
        }
    }

    fun rowFor(index: Int, track: Track) = TrackRowUi(
        originalIndex = index,
        track = track,
        title = "${index + 1}. ${track.title}",
        subtitle = listOf(track.artist, track.album.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" • ")
            .ifEmpty { "—" },
        durationText = formatDuration(track.durationMs),
        sourceLabel = sourceLabel(track.source),
    )

    /** Clipboard lines of `title\tartist\talbum`; tabs/newlines inside a field become a space. */
    fun clipboardText(tracks: List<Track>): String = tracks.joinToString("\n") { track ->
        listOf(track.title, track.artist, track.album.orEmpty())
            .joinToString("\t") { it.replace(FIELD_SEPARATORS, " ") }
    }

    private val FIELD_SEPARATORS = Regex("[\t\r\n]+")
}

/** `m:ss`, or `h:mm:ss` past an hour. */
fun formatDuration(durationMs: Long?): String {
    val totalSeconds = (durationMs ?: 0L).coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

fun sourceLabel(source: SourceType): String = when (source) {
    SourceType.SPOTIFY -> "Spotify"
    SourceType.JELLYFIN -> "Jellyfin"
    SourceType.PLEX -> "Plex"
    SourceType.CUSTOM -> "Custom"
    SourceType.ALL -> "All"
}

/** "320 kbps • 44.1 kHz", or null when nothing is known. */
fun qualityLabel(track: Track): String? {
    val parts = ArrayList<String>(2)
    val bitrate = track.bitrateKbps ?: 0
    if (bitrate > 0) parts += "$bitrate kbps"
    val sampleRate = track.sampleRateHz ?: 0
    if (sampleRate > 0) {
        val khz = sampleRate / 1000.0
        parts += if (khz % 1.0 == 0.0) "${khz.toInt()} kHz" else String.format(Locale.ROOT, "%.1f kHz", khz)
    }
    return if (parts.isEmpty()) null else parts.joinToString(" • ")
}
