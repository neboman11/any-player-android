package com.anyplayer.android.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.anyplayer.android.core.model.RepeatMode

@Composable
internal fun NowPlayingSection(viewModel: MainViewModel, state: MainUiState) {
    val status = state.playbackStatus
    val playbackDisabledMessage = state.playbackDisabledMessage
    val currentTrackId = status.currentTrack?.id
    val displayQueue = status.orderedQueue.ifEmpty { status.queue }
    val originalQueue = status.queue

    val currentIdx = displayQueue.indexOfFirst { it.id == currentTrackId }
    val upcomingTracks = if (currentIdx >= 0) displayQueue.drop(currentIdx + 1) else displayQueue
    val pastTracks    = if (currentIdx >  0) displayQueue.take(currentIdx)     else emptyList()

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                status.currentTrack?.imageUrl?.takeIf { it.isNotBlank() }?.let { artworkUrl ->
                    ElevatedCard(
                        modifier = Modifier.size(72.dp),
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
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = status.currentTrack?.title ?: "-",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = status.currentTrack?.artist ?: "-",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                    Text(
                        text = status.state.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::previous) { Text("Previous") }
                Button(
                    onClick = viewModel::togglePlayPause,
                    enabled = playbackDisabledMessage == null
                ) { Text("Play/Pause") }
                Button(onClick = viewModel::next) { Text("Next") }
            }
        }

        item {
            Slider(
                value = status.position.toFloat(),
                valueRange = 0f..(status.duration.takeIf { it > 0 } ?: 1L).toFloat(),
                onValueChange = { viewModel.seekTo(it.toLong()) }
            )
        }
        item {
            Slider(
                value = status.volume.toFloat(),
                valueRange = 0f..100f,
                onValueChange = { viewModel.setVolume(it.toInt()) }
            )
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
                        label = { Text(mode.name) }
                    )
                }
            }
        }

        if (upcomingTracks.isNotEmpty() || pastTracks.isNotEmpty()) {
            item { Text("Up Next") }
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
            item { Text("No queue loaded", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
