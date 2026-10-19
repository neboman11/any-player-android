package com.anyplayer.android.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.dj.PreparationStatus
import com.anyplayer.android.ui.components.Artwork
import com.anyplayer.android.ui.components.Pill
import com.anyplayer.android.ui.components.ScreenPadding
import com.anyplayer.android.ui.components.SectionTitle
import com.anyplayer.android.ui.components.SourceBadge
import com.anyplayer.android.ui.components.formatDuration
import kotlinx.coroutines.delay

private const val POSITION_TICK_MS = 250L

@Stable
class NowPlayingActions(
    val positionMs: () -> Long,
    val onTogglePlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onVolume: (Int) -> Unit,
    val onToggleShuffle: () -> Unit,
    val onRepeat: (RepeatMode) -> Unit,
    val onPlayEntry: (Long) -> Unit,
)

@Composable
fun NowPlayingRoute(viewModel: NowPlayingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        NowPlayingActions(
            positionMs = viewModel::positionMs,
            onTogglePlayPause = viewModel::togglePlayPause,
            onNext = viewModel::next,
            onPrevious = viewModel::previous,
            onSeek = viewModel::seekTo,
            onVolume = viewModel::setVolume,
            onToggleShuffle = viewModel::toggleShuffle,
            onRepeat = viewModel::setRepeat,
            onPlayEntry = viewModel::playEntry,
        )
    }
    NowPlayingScreen(state, actions)
}

@Composable
fun NowPlayingScreen(state: NowPlayingUi, actions: NowPlayingActions) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header", contentType = "header") { Header(state) }
        state.disabledReason?.let { reason ->
            item(key = "disabled", contentType = "banner") {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        state.error?.let { error ->
            item(key = "error", contentType = "error") {
                Text("Error: $error", color = MaterialTheme.colorScheme.error)
            }
        }
        item(key = "position", contentType = "position") {
            PositionSection(
                isPlaying = state.isPlaying,
                anchorPositionMs = state.anchorPositionMs,
                anchorElapsedMs = state.anchorElapsedMs,
                durationMs = state.durationMs,
                canSeek = state.canSeek,
                positionMs = actions.positionMs,
                onSeek = actions.onSeek,
            )
        }
        item(key = "transport", contentType = "transport") {
            Transport(
                isPlaying = state.isPlaying,
                playEnabled = state.disabledReason == null,
                actions = actions,
            )
        }
        item(key = "volume", contentType = "volume") { VolumeRow(state.volume, actions.onVolume) }
        item(key = "modes", contentType = "modes") {
            ModeChips(state.shuffle, state.repeat, actions.onToggleShuffle, actions.onRepeat)
        }
        queueItems(state.queue, actions.onPlayEntry)
    }
}

@Composable
private fun Header(state: NowPlayingUi) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f)) {
            if (state.djBreak) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.RecordVoiceOver,
                        contentDescription = "AI DJ",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(96.dp),
                    )
                }
            } else {
                Artwork(state.imageUrl, size = 320.dp, modifier = Modifier.fillMaxSize(), cornerRadius = 20.dp)
            }
        }
        Spacer(Modifier.size(16.dp))
        Text(
            state.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            state.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(8.dp))
        Pill(
            text = statusLabel(state.status),
            color = if (state.isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

private fun statusLabel(status: PlaybackStateType): String = when (status) {
    PlaybackStateType.IDLE -> "Idle"
    PlaybackStateType.PLAYING -> "Playing"
    PlaybackStateType.PAUSED -> "Paused"
    PlaybackStateType.BUFFERING -> "Buffering"
    PlaybackStateType.ERROR -> "Error"
}

/** Reads the engine position on a ticker that runs only while started and playing. */
@Composable
private fun PositionSection(
    isPlaying: Boolean,
    anchorPositionMs: Long,
    anchorElapsedMs: Long,
    durationMs: Long,
    canSeek: Boolean,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val position by produceState(initialValue = anchorPositionMs, isPlaying, anchorPositionMs, anchorElapsedMs, lifecycleOwner) {
        value = positionMs()
        if (isPlaying) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    value = positionMs()
                    delay(POSITION_TICK_MS)
                }
            }
        }
    }
    val drag = remember { SliderDragState() }
    val max = durationMs.coerceAtLeast(1L).toFloat()
    val shown = drag.displayValue(position.toFloat()).coerceIn(0f, max)
    Column {
        Slider(
            value = shown,
            onValueChange = drag::onValueChange,
            onValueChangeFinished = { drag.onValueChangeFinished { onSeek(it.toLong()) } },
            valueRange = 0f..max,
            enabled = canSeek,
            modifier = Modifier.semantics { contentDescription = "Playback position" },
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(formatDuration(shown.toLong()), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Transport(isPlaying: Boolean, playEnabled: Boolean, actions: NowPlayingActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Previous/Next stay enabled while playback is disabled ("Next/Previous still works").
        IconButton(onClick = actions.onPrevious, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(32.dp))
        }
        FilledIconButton(onClick = actions.onTogglePlayPause, enabled = playEnabled, modifier = Modifier.size(72.dp)) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = actions.onNext, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun VolumeRow(volume: Int, onVolume: (Int) -> Unit) {
    val drag = remember { SliderDragState() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "Volume down")
        Slider(
            value = drag.displayValue(volume.toFloat()),
            onValueChange = drag::onValueChange,
            onValueChangeFinished = { drag.onValueChangeFinished { onVolume(it.toInt()) } },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Volume" },
        )
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Volume up")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeChips(
    shuffle: Boolean,
    repeat: RepeatMode,
    onToggleShuffle: () -> Unit,
    onRepeat: (RepeatMode) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(
            selected = shuffle,
            onClick = onToggleShuffle,
            label = { Text("Shuffle") },
            leadingIcon = { Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        RepeatMode.entries.forEach { mode ->
            FilterChip(
                selected = repeat == mode,
                onClick = { onRepeat(mode) },
                label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                leadingIcon = when (mode) {
                    RepeatMode.OFF -> null
                    RepeatMode.ONE -> {
                        { Icon(Icons.Filled.RepeatOne, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    }
                    RepeatMode.ALL -> {
                        { Icon(Icons.Filled.Repeat, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    }
                },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.queueItems(queue: QueueUi, onPlayEntry: (Long) -> Unit) {
    if (queue.isEmpty) {
        item(key = "queue_empty", contentType = "text") {
            Text("No queue loaded", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    item(key = "up_next", contentType = "title") { SectionTitle("Up Next") }
    items(queue.upcoming, key = { it.key }, contentType = { if (it.isDj) "dj" else "queue_row" }) { row ->
        QueueRow(row, subdued = false, onPlayEntry = onPlayEntry)
    }
    if (queue.history.isNotEmpty()) {
        item(key = "history_title", contentType = "title") {
            val count = queue.history.size
            SectionTitle(if (count == 1) "1 track already played" else "$count tracks already played")
        }
        items(queue.history, key = { "history_${it.key}" }, contentType = { "queue_row" }) { row ->
            QueueRow(row, subdued = true, onPlayEntry = onPlayEntry)
        }
    }
}

@Composable
private fun QueueRow(row: QueueRowUi, subdued: Boolean, onPlayEntry: (Long) -> Unit) {
    val background = when (row.djStatus) {
        null -> MaterialTheme.colorScheme.surface
        PreparationStatus.NOT_STARTED -> MaterialTheme.colorScheme.surfaceVariant
        PreparationStatus.PROCESSING -> MaterialTheme.colorScheme.secondaryContainer
        PreparationStatus.READY -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val uid = row.uid
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .then(if (uid != null) Modifier.clickable { onPlayEntry(uid) } else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .alpha(if (subdued) 0.6f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (row.isDj) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiary)
            }
        } else {
            Artwork(row.imageUrl, size = 40.dp, cornerRadius = 6.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(row.durationText, style = MaterialTheme.typography.labelMedium)
            row.source?.let { SourceBadge(it) }
        }
    }
}
