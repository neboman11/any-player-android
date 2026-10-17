package com.anyplayer.android.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.ui.components.AddToPlaylistDialog
import com.anyplayer.android.ui.components.MessagesEffect
import com.anyplayer.android.ui.components.PlaylistChoice
import com.anyplayer.android.ui.components.RefreshFeedback
import com.anyplayer.android.ui.components.SourceBadge
import com.anyplayer.android.ui.components.TrackRowAction
import com.anyplayer.android.ui.components.TrackSortColumn
import com.anyplayer.android.ui.components.TrackTable

private val ProviderRowActions = listOf(TrackRowAction.PLAY, TrackRowAction.ADD_TO_QUEUE, TrackRowAction.ADD_TO_PLAYLIST)

@Composable
fun ProviderPlaylistRoute(viewModel: ProviderPlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    MessagesEffect(viewModel.messages)
    ProviderPlaylistScreen(
        state = state,
        filter = filter,
        onFilterChange = viewModel::setFilter,
        onSortClick = viewModel::toggleSort,
        onPlayAll = viewModel::playAll,
        onPlayFrom = viewModel::playFrom,
        onAddToQueue = viewModel::addToQueue,
        onAddToPlaylist = viewModel::addToPlaylist,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onDistinctChange = viewModel::setDistinct,
        clipboardText = viewModel::clipboardText,
    )
}

@Composable
fun ProviderPlaylistScreen(
    state: ProviderDetailUi,
    filter: String,
    onFilterChange: (String) -> Unit,
    onSortClick: (TrackSortColumn) -> Unit,
    onPlayAll: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (PlaylistChoice, Track) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onDistinctChange: (Boolean) -> Unit,
    clipboardText: suspend () -> String,
) {
    var pickerTrack by remember { mutableStateOf<Track?>(null) }

    TrackTable(
        table = state.table,
        actions = ProviderRowActions,
        onAction = { action, row ->
            when (action) {
                TrackRowAction.PLAY -> onPlayFrom(row.originalIndex)
                TrackRowAction.ADD_TO_QUEUE -> onAddToQueue(row.track)
                TrackRowAction.ADD_TO_PLAYLIST -> pickerTrack = row.track
                TrackRowAction.REMOVE -> Unit
            }
        },
        onSortClick = onSortClick,
        filter = filter,
        onFilterChange = onFilterChange,
        emptyText = if (state.loading) null else "No tracks found for this playlist",
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "title", contentType = "title") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f, fill = false))
                SourceBadge(state.source)
            }
        }
        item(key = "actions", contentType = "actions") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlayAll, enabled = state.table.totalCount > 0) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Play Playlist")
                }
                OutlinedButton(onClick = onRefresh, enabled = !state.refresh.inProgress) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (state.refresh.inProgress) "Refreshing..." else "Refresh")
                }
            }
        }
        item(key = "count", contentType = "count") { TrackCountRow(state.table.totalCount, clipboardText) }
        item(key = "distinct", contentType = "distinct") { DistinctRow(state.distinct, onDistinctChange) }
        item(key = "refresh", contentType = "feedback") {
            RefreshFeedback(state.refresh.inProgress, state.refresh.status, "Refreshing...")
        }
        if (state.loading) {
            item(key = "loading", contentType = "loading") { LoadingRow("Loading tracks...") }
        }
        state.error?.let { error ->
            item(key = "error", contentType = "error") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
        duplicateGroupItems(state.duplicates, onRemove = null)
    }

    pickerTrack?.let { track ->
        AddToPlaylistDialog(
            trackTitle = track.title,
            choices = state.standardPlaylists,
            onPick = { onAddToPlaylist(it, track) },
            onDismiss = { pickerTrack = null },
        )
    }
}
