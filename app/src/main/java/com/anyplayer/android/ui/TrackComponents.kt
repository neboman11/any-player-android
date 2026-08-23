package com.anyplayer.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track

internal enum class TrackSortColumn { TITLE, ARTIST, ALBUM, DURATION, SOURCE }

internal data class IndexedTrackRow(
    val originalIndex: Int,
    val track: Track
)

internal fun sortTracksWithOriginalIndex(
    tracks: List<Track>,
    sortColumn: TrackSortColumn?,
    sortAscending: Boolean
): List<IndexedTrackRow> {
    val indexedTracks = tracks.mapIndexed { index, track -> IndexedTrackRow(index, track) }
    if (sortColumn == null) return indexedTracks

    val sorted = indexedTracks.sortedWith { left, right ->
        val comparison = when (sortColumn) {
            TrackSortColumn.TITLE -> left.track.title.compareTo(right.track.title, ignoreCase = true)
            TrackSortColumn.ARTIST -> left.track.artist.compareTo(right.track.artist, ignoreCase = true)
            TrackSortColumn.ALBUM -> (left.track.album ?: "").compareTo(right.track.album ?: "", ignoreCase = true)
            TrackSortColumn.DURATION -> (left.track.durationMs ?: 0L).compareTo(right.track.durationMs ?: 0L)
            TrackSortColumn.SOURCE -> left.track.source.name.compareTo(right.track.source.name, ignoreCase = true)
        }
        if (sortAscending) comparison else -comparison
    }

    return sorted
}

internal fun getTrackSourceLabel(source: SourceType): String = when (source) {
    SourceType.SPOTIFY -> "Spotify"
    SourceType.JELLYFIN -> "Jellyfin"
    SourceType.PLEX -> "Plex"
    SourceType.CUSTOM -> "Custom"
    SourceType.ALL -> "All"
}

internal fun getTrackQualityLabel(track: Track): String {
    val parts = mutableListOf<String>()

    if ((track.bitrateKbps ?: 0) > 0) {
        parts += "${track.bitrateKbps} kbps"
    }

    val sampleRateHz = track.sampleRateHz ?: 0
    if (sampleRateHz > 0) {
        val sampleRateKhz = sampleRateHz / 1000.0
        val sampleRateLabel = if (sampleRateKhz % 1.0 == 0.0) {
            "${sampleRateKhz.toInt()} kHz"
        } else {
            "${"%.1f".format(sampleRateKhz)} kHz"
        }
        parts += sampleRateLabel
    }

    return if (parts.isEmpty()) "Unknown" else parts.joinToString(" • ")
}

internal fun isSpotifyQualityUnavailable(track: Track): Boolean {
    val hasBitrate = (track.bitrateKbps ?: 0) > 0
    val hasSampleRate = (track.sampleRateHz ?: 0) > 0
    return track.source == SourceType.SPOTIFY && !hasBitrate && !hasSampleRate
}

internal fun formatTrackDuration(durationMs: Long?): String {
    val totalSeconds = (durationMs ?: 0L) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

@Composable
internal fun TrackActionMenu(actions: List<Pair<String, () -> Unit>>) {
    Box {
        var expanded by remember { mutableStateOf(false) }
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Track actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { (label, onClick) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onClick(); expanded = false }
                )
            }
        }
    }
}

@Composable
internal fun TrackRow(
    track: Track,
    indexLabel: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = "$indexLabel ${track.title}", modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
        Text(text = track.artist, modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
        Text(text = track.album ?: "—", modifier = Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall)
        Text(text = formatTrackDuration(track.durationMs), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TrackSourceBadge(source = track.source, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun QueueTableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Track", modifier = Modifier.weight(2f), style = MaterialTheme.typography.labelMedium)
        Text("Artist", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium)
        Text("Source", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        Text("Quality", modifier = Modifier.weight(1.3f), style = MaterialTheme.typography.labelMedium)
        Text("Duration", modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.labelMedium)
        Text("Action", style = MaterialTheme.typography.labelMedium)
    }
    HorizontalDivider()
}

@Composable
internal fun QueueTrackRow(
    track: Track,
    onPlay: () -> Unit,
    subdued: Boolean = false
) {
    val textColor = if (subdued) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(track.title, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall, color = textColor)
        Text(track.artist, modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall, color = textColor)
        TrackSourceBadge(source = track.source, modifier = Modifier.weight(1f))
        TrackQualityBadge(track = track, modifier = Modifier.weight(1.3f))
        Text(formatTrackDuration(track.durationMs), modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall, color = textColor)
        TrackActionMenu(listOf("Play" to onPlay))
    }
}

@Composable
internal fun TrackSourceBadge(source: SourceType, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = getTrackSourceLabel(source),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
internal fun TrackQualityBadge(track: Track, modifier: Modifier = Modifier) {
    val label = getTrackQualityLabel(track)
    val qualityText = if (isSpotifyQualityUnavailable(track)) "$label ℹ" else label
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = qualityText,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
internal fun TrackSortableHeaderRow(
    sortColumn: TrackSortColumn?,
    sortAscending: Boolean,
    onSort: (TrackSortColumn) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TrackSortableHeader(
            label = "Title",
            isActive = sortColumn == TrackSortColumn.TITLE,
            ascending = sortAscending,
            modifier = Modifier.weight(2f),
            onClick = { onSort(TrackSortColumn.TITLE) }
        )
        TrackSortableHeader(
            label = "Artist",
            isActive = sortColumn == TrackSortColumn.ARTIST,
            ascending = sortAscending,
            modifier = Modifier.weight(1.5f),
            onClick = { onSort(TrackSortColumn.ARTIST) }
        )
        TrackSortableHeader(
            label = "Album",
            isActive = sortColumn == TrackSortColumn.ALBUM,
            ascending = sortAscending,
            modifier = Modifier.weight(1.3f),
            onClick = { onSort(TrackSortColumn.ALBUM) }
        )
        TrackSortableHeader(
            label = "Duration",
            isActive = sortColumn == TrackSortColumn.DURATION,
            ascending = sortAscending,
            modifier = Modifier.weight(1f),
            onClick = { onSort(TrackSortColumn.DURATION) }
        )
        TrackSortableHeader(
            label = "Source",
            isActive = sortColumn == TrackSortColumn.SOURCE,
            ascending = sortAscending,
            modifier = Modifier.weight(1f),
            onClick = { onSort(TrackSortColumn.SOURCE) }
        )
    }
    HorizontalDivider()
}

@Composable
private fun TrackSortableHeader(
    label: String,
    isActive: Boolean,
    ascending: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            text = if (isActive) "$label ${if (ascending) "↑" else "↓"}" else label,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
