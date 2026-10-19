package com.anyplayer.android.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.ui.components.AddToPlaylistDialog
import com.anyplayer.android.ui.components.ConfirmDialog
import com.anyplayer.android.ui.components.MessagesEffect
import com.anyplayer.android.ui.components.Pill
import com.anyplayer.android.ui.components.PlaylistChoice
import com.anyplayer.android.ui.components.RefreshFeedback
import com.anyplayer.android.ui.components.ScreenPadding
import com.anyplayer.android.ui.components.SectionTitle
import com.anyplayer.android.ui.components.TrackRowAction
import com.anyplayer.android.ui.components.TrackSortColumn
import com.anyplayer.android.ui.components.TrackTable

private val StandardRowActions = listOf(
    TrackRowAction.PLAY,
    TrackRowAction.ADD_TO_QUEUE,
    TrackRowAction.ADD_TO_PLAYLIST,
    TrackRowAction.REMOVE,
)
private val UnionRowActions = listOf(TrackRowAction.PLAY, TrackRowAction.ADD_TO_QUEUE, TrackRowAction.ADD_TO_PLAYLIST)

@Composable
fun CustomPlaylistRoute(viewModel: CustomPlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    MessagesEffect(viewModel.messages)
    val actions = remember(viewModel) {
        CustomPlaylistActions(
            onFilterChange = viewModel::setFilter,
            onSortClick = viewModel::toggleSort,
            onPlayAll = viewModel::playAll,
            onPlayFrom = viewModel::playFrom,
            onAddToQueue = viewModel::addToQueue,
            onAddToPlaylist = viewModel::addToPlaylist,
            onRemoveAt = viewModel::removeTrackAt,
            onDistinctChange = viewModel::setDistinct,
            onMaterialize = viewModel::materialize,
            onSaveSources = viewModel::saveUnionSources,
            clipboardText = viewModel::clipboardText,
        )
    }
    CustomPlaylistScreen(state = state, filter = filter, actions = actions)
}

@Stable
class CustomPlaylistActions(
    val onFilterChange: (String) -> Unit,
    val onSortClick: (TrackSortColumn) -> Unit,
    val onPlayAll: () -> Unit,
    val onPlayFrom: (Int) -> Unit,
    val onAddToQueue: (Track) -> Unit,
    val onAddToPlaylist: (PlaylistChoice, Track) -> Unit,
    val onRemoveAt: (Int) -> Unit,
    val onDistinctChange: (Boolean) -> Unit,
    val onMaterialize: () -> Unit,
    val onSaveSources: (List<String>) -> Unit,
    val clipboardText: suspend () -> String,
)

@Composable
fun CustomPlaylistScreen(state: CustomDetailUi, filter: String, actions: CustomPlaylistActions) {
    if (!state.exists) {
        Text("Playlist not found", modifier = Modifier.padding(ScreenPadding))
        return
    }
    val isUnion = state.type == PlaylistType.UNION
    var pickerTrack by remember { mutableStateOf<Track?>(null) }
    var pendingTrackRemoval by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingDuplicateRemoval by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingSources by rememberSaveable { mutableStateOf(false) }

    TrackTable(
        table = state.table,
        actions = if (isUnion) UnionRowActions else StandardRowActions,
        onAction = { action, row ->
            when (action) {
                TrackRowAction.PLAY -> actions.onPlayFrom(row.originalIndex)
                TrackRowAction.ADD_TO_QUEUE -> actions.onAddToQueue(row.track)
                TrackRowAction.ADD_TO_PLAYLIST -> pickerTrack = row.track
                TrackRowAction.REMOVE -> pendingTrackRemoval = row.originalIndex
            }
        },
        onSortClick = actions.onSortClick,
        filter = filter,
        onFilterChange = actions.onFilterChange,
        emptyText = if (state.loaded) "No tracks found for this playlist" else null,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "title", contentType = "title") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f, fill = false))
                Pill(state.type.name.lowercase())
            }
        }
        item(key = "actions", contentType = "actions") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onPlayAll, enabled = state.table.totalCount > 0 || isUnion) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Play")
                }
                if (isUnion) {
                    OutlinedButton(onClick = actions.onMaterialize, enabled = !state.refresh.inProgress) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(if (state.refresh.inProgress) "Refreshing..." else "Refresh")
                    }
                    OutlinedButton(onClick = { editingSources = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Edit sources")
                    }
                }
            }
        }
        item(key = "count", contentType = "count") { TrackCountRow(state.table.totalCount, actions.clipboardText) }
        item(key = "distinct", contentType = "distinct") { DistinctRow(state.distinct, actions.onDistinctChange) }
        if (isUnion) {
            item(key = "refresh", contentType = "feedback") {
                RefreshFeedback(state.refresh.inProgress, state.refresh.status, "Materializing union...")
            }
            item(key = "sources", contentType = "sources") {
                Column {
                    SectionTitle("Source playlists")
                    if (state.sourceLabels.isEmpty()) {
                        Text("No source playlists added")
                    } else {
                        state.sourceLabels.forEachIndexed { index, label ->
                            Text("${index + 1}. $label", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        duplicateGroupItems(
            state.duplicates,
            onRemove = if (isUnion) null else { index -> pendingDuplicateRemoval = index },
        )
    }

    pickerTrack?.let { track ->
        AddToPlaylistDialog(
            trackTitle = track.title,
            choices = state.standardPlaylists,
            onPick = { actions.onAddToPlaylist(it, track) },
            onDismiss = { pickerTrack = null },
        )
    }
    pendingTrackRemoval?.let { index ->
        ConfirmDialog(
            title = "Remove track?",
            text = "This removes the track from the playlist.",
            confirmLabel = "Remove",
            onConfirm = { actions.onRemoveAt(index) },
            onDismiss = { pendingTrackRemoval = null },
        )
    }
    pendingDuplicateRemoval?.let { index ->
        ConfirmDialog(
            title = "Remove duplicate?",
            text = "This removes the selected duplicate occurrence from the playlist.",
            confirmLabel = "Remove",
            onConfirm = { actions.onRemoveAt(index) },
            onDismiss = { pendingDuplicateRemoval = null },
        )
    }
    if (editingSources) {
        EditUnionSourcesDialog(
            candidates = state.sourceCandidates,
            initialKeys = state.selectedSourceKeys,
            onSave = actions.onSaveSources,
            onDismiss = { editingSources = false },
        )
    }
}

@Composable
private fun EditUnionSourcesDialog(
    candidates: List<UnionSourceCandidate>,
    initialKeys: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Insertion-ordered: the union's source order is the order of checking.
    var selected by remember(initialKeys) { mutableStateOf<Set<String>>(LinkedHashSet(initialKeys)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit union sources") },
        text = {
            if (candidates.isEmpty()) {
                Text("No playlists available. Refresh provider playlists or create a standard playlist.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(candidates, key = { it.key }) { candidate ->
                        val checked = candidate.key in selected
                        val toggle = {
                            selected = if (checked) selected - candidate.key else LinkedHashSet(selected).apply { add(candidate.key) }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(onClick = toggle),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggle() })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(candidate.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    candidate.source.name.lowercase(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(selected.toList())
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
