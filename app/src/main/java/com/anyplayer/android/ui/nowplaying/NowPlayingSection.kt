package com.anyplayer.android.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.anyplayer.android.app.MainUiState
import com.anyplayer.android.app.MainViewModel
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.ui.QueueTableHeader
import com.anyplayer.android.ui.QueueTrackRow
import com.anyplayer.android.ui.TrackRow
import com.anyplayer.android.ui.formatTrackDuration

@Composable
internal fun NowPlayingSection(viewModel: MainViewModel, state: MainUiState) {
    val status = state.playbackStatus
    val playbackDisabledMessage = state.playbackDisabledMessage
    val nowPlayingOverride by viewModel.nowPlayingOverride.collectAsState()
    // While an AI DJ break is playing, show it instead of the real track - but the
    // up-next/history queue split below still tracks the real track's position, since
    // the break is never actually part of the domain queue.
    val displayTrack = nowPlayingOverride ?: status.currentTrack
    val currentTrackId = status.currentTrack?.id
    val displayQueue = status.orderedQueue.ifEmpty { status.queue }
    val originalQueue = status.queue

    val currentIdx = displayQueue.indexOfFirst { it.id == currentTrackId }
    val upcomingTracks = if (currentIdx >= 0) displayQueue.drop(currentIdx + 1) else displayQueue
    val pastTracks    = if (currentIdx >  0) displayQueue.take(currentIdx)     else emptyList()

    val isPlaying = status.state == PlaybackStateType.PLAYING

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                displayTrack?.imageUrl?.takeIf { it.isNotBlank() }?.let { artworkUrl ->
                    ElevatedCard(
                        modifier = Modifier.size(120.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        AsyncImage(
                            model = artworkUrl,
                            contentDescription = "Album artwork",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = displayTrack?.title ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = displayTrack?.artist ?: "—",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        shape = CircleShape,
                        color = if (isPlaying) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            text = when (status.state) {
                                PlaybackStateType.IDLE      -> "Idle"
                                PlaybackStateType.PLAYING   -> "Playing"
                                PlaybackStateType.PAUSED    -> "Paused"
                                PlaybackStateType.BUFFERING -> "Buffering"
                                PlaybackStateType.ERROR     -> "Error"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        if (playbackDisabledMessage != null) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = playbackDisabledMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }
        status.errorMessage?.let { err ->
            item {
                Text(
                    text = "Error: $err",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = viewModel::previous) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(32.dp)
                    )
                }
                FilledIconButton(
                    onClick = viewModel::togglePlayPause,
                    enabled = playbackDisabledMessage == null,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = viewModel::next) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTrackDuration(status.position),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatTrackDuration(status.duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = status.position.toFloat(),
                    valueRange = 0f..(status.duration.takeIf { it > 0 } ?: 1L).toFloat(),
                    onValueChange = { viewModel.seekTo(it.toLong()) }
                )
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeDown,
                    contentDescription = "Volume down",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = status.volume.toFloat(),
                    valueRange = 0f..100f,
                    onValueChange = { viewModel.setVolume(it.toInt()) },
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Volume up",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = status.shuffle,
                    onClick = { viewModel.setShuffle(!status.shuffle) },
                    label = { Text("Shuffle") }
                )
                RepeatMode.entries.forEach { mode ->
                    FilterChip(
                        selected = status.repeatMode == mode,
                        onClick = { viewModel.setRepeatMode(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
        }

        if (upcomingTracks.isNotEmpty() || pastTracks.isNotEmpty()) {
            item { Text("Up Next", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            item { QueueTableHeader() }
            items(upcomingTracks, key = { it.id }) { track ->
                QueueTrackRow(
                    track = track,
                    onPlay = {
                        val originalIdx = originalQueue.indexOfFirst { it.id == track.id }
                        if (originalIdx >= 0) viewModel.playFromQueue(originalIdx)
                    }
                )
            }

            if (pastTracks.isNotEmpty()) {
                item {
                    Text(
                        "${pastTracks.size} track${if (pastTracks.size == 1) "" else "s"} already played",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item { QueueTableHeader() }
                items(pastTracks, key = { it.id }) { track ->
                    QueueTrackRow(
                        track = track,
                        onPlay = {
                            val originalIdx = originalQueue.indexOfFirst { it.id == track.id }
                            if (originalIdx >= 0) viewModel.playFromQueue(originalIdx)
                        },
                        subdued = true
                    )
                }
            }
        } else if (displayQueue.isEmpty()) {
            item {
                Text(
                    "No queue loaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
