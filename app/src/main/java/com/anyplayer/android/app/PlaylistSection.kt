package com.anyplayer.android.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.UnionPlaylistSource
import java.time.Instant
import java.util.UUID

@Composable
internal fun PlaylistSection(viewModel: MainViewModel, state: MainUiState) {
    var newStandardName by remember { mutableStateOf("") }
    var newUnionName by remember { mutableStateOf("") }
    var providerTrackSortColumnName by rememberSaveable("playlist_provider_track_sort_column") { mutableStateOf<String?>(null) }
    var providerTrackSortAscending by rememberSaveable("playlist_provider_track_sort_ascending") { mutableStateOf(true) }
    var customTrackSortColumnName by rememberSaveable("playlist_custom_track_sort_column") { mutableStateOf<String?>(null) }
    var customTrackSortAscending by rememberSaveable("playlist_custom_track_sort_ascending") { mutableStateOf(true) }
    var pendingStandardDuplicateRemovalIndex by remember { mutableStateOf<Int?>(null) }
    var pendingStandardTrackRemovalIndex by remember { mutableStateOf<Int?>(null) }
    var providerPlaylistSearchQuery by rememberSaveable("playlist_provider_search") { mutableStateOf("") }
    var customPlaylistSearchQuery by rememberSaveable("playlist_custom_search") { mutableStateOf("") }

    val selectedProviderPlaylist = state.selectedProviderPlaylist
    val selectedCustomPlaylist = state.customPlaylists.firstOrNull { it.id == state.selectedCustomPlaylistId }
    val providerTrackSortColumn = providerTrackSortColumnName?.let { sortName ->
        TrackSortColumn.entries.firstOrNull { it.name == sortName }
    }
    val customTrackSortColumn = customTrackSortColumnName?.let { sortName ->
        TrackSortColumn.entries.firstOrNull { it.name == sortName }
    }

    if (selectedProviderPlaylist != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::closeProviderPlaylistSummary) { Text("Back") }
                Button(onClick = viewModel::playSelectedProviderPlaylist) { Text("Play Playlist") }
                Button(
                    onClick = viewModel::refreshProviderPlaylistData,
                    enabled = !state.providerPlaylistRefreshInProgress
                ) {
                    if (state.providerPlaylistRefreshInProgress) {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                        Text(" Refreshing...")
                    } else {
                        Text("Refresh")
                    }
                }
            }

            Text("${selectedProviderPlaylist.name} (${selectedProviderPlaylist.source.name.lowercase()})")
            Text("Tracks: ${state.selectedProviderPlaylistTracks.size}")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Distinct playback")
                Switch(
                    checked = state.selectedProviderPlaylistIsDistinct,
                    onCheckedChange = viewModel::setSelectedProviderPlaylistDistinct
                )
            }

            RefreshFeedback(
                inProgress = state.providerPlaylistRefreshInProgress,
                status = state.providerPlaylistRefreshStatus,
                inProgressLabel = "Refreshing..."
            )

            if (state.selectedProviderPlaylistLoading) {
                Text("Loading tracks...")
            }

            state.selectedProviderPlaylistError?.let { error ->
                Text(error)
                Button(onClick = { viewModel.openProviderPlaylistSummary(selectedProviderPlaylist) }) {
                    Text("Retry")
                }
            }

            if (!state.selectedProviderPlaylistLoading && state.selectedProviderPlaylistTracks.isEmpty()) {
                Text("No tracks found for this playlist")
            } else {
                if (state.selectedProviderPlaylistIsDistinct && state.selectedProviderPlaylistDuplicateGroups.isNotEmpty()) {
                    DuplicateGroupsSection(
                        title = "Duplicates",
                        tracks = state.selectedProviderPlaylistTracks,
                        duplicateGroups = state.selectedProviderPlaylistDuplicateGroups
                    )
                }

                val sortedProviderTracks = remember(
                    state.selectedProviderPlaylistTracks,
                    providerTrackSortColumnName,
                    providerTrackSortAscending
                ) {
                    sortTracksWithOriginalIndex(
                        tracks = state.selectedProviderPlaylistTracks,
                        sortColumn = providerTrackSortColumn,
                        sortAscending = providerTrackSortAscending
                    )
                }

                val filteredProviderTracks = remember(sortedProviderTracks, providerPlaylistSearchQuery) {
                    if (providerPlaylistSearchQuery.isBlank()) sortedProviderTracks
                    else {
                        val q = providerPlaylistSearchQuery.lowercase()
                        sortedProviderTracks.filter { row ->
                            row.track.title.lowercase().contains(q) ||
                                row.track.artist.lowercase().contains(q) ||
                                (row.track.album?.lowercase()?.contains(q) == true)
                        }
                    }
                }

                OutlinedTextField(
                    value = providerPlaylistSearchQuery,
                    onValueChange = { providerPlaylistSearchQuery = it },
                    label = { Text("Search tracks") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                TrackSortableHeaderRow(
                    sortColumn = providerTrackSortColumn,
                    sortAscending = providerTrackSortAscending,
                    onSort = { column ->
                        if (providerTrackSortColumn != column) {
                            providerTrackSortColumnName = column.name
                            providerTrackSortAscending = true
                        } else if (providerTrackSortAscending) {
                            providerTrackSortAscending = false
                        } else {
                            providerTrackSortColumnName = null
                            providerTrackSortAscending = true
                        }
                    }
                )

                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(filteredProviderTracks, key = { _, row -> row.track.id }) { _, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TrackRow(track = row.track, indexLabel = "${row.originalIndex + 1}.", modifier = Modifier.weight(1f))
                            TrackActionMenu(listOf("Add to Queue" to { viewModel.addToQueue(row.track) }))
                        }
                    }
                }
            }
        }
        return
    }

    if (selectedCustomPlaylist != null) {
        val unionSourceLabels = if (selectedCustomPlaylist.playlistType == PlaylistType.UNION) {
            state.selectedCustomUnionSources.map { source ->
                val normalizedSpotifySourceId = if (source.sourceType == SourceType.SPOTIFY) {
                    source.sourcePlaylistId
                        .substringAfter("spotify:playlist:", source.sourcePlaylistId)
                        .let { value ->
                            value.substringAfter("/playlist/", value)
                                .substringBefore('?')
                                .substringBefore('/')
                        }
                } else {
                    source.sourcePlaylistId
                }
                val playlistName = when (source.sourceType) {
                    SourceType.CUSTOM -> {
                        state.customPlaylists.firstOrNull { it.id == source.sourcePlaylistId }?.name
                    }
                    SourceType.JELLYFIN,
                    SourceType.PLEX,
                    SourceType.SPOTIFY -> {
                        state.providerPlaylists.firstOrNull {
                            it.source == source.sourceType && (
                                it.id == source.sourcePlaylistId ||
                                    (source.sourceType == SourceType.SPOTIFY && it.id == normalizedSpotifySourceId)
                                )
                        }?.name
                    }
                    SourceType.ALL -> null
                } ?: source.sourcePlaylistId
                "$playlistName (${source.sourceType.name.lowercase()})"
            }
        } else {
            emptyList()
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::closeCustomPlaylistDetails) { Text("Back") }
                Button(onClick = { viewModel.playCustomPlaylist(selectedCustomPlaylist.id) }) { Text("Play") }
                if (selectedCustomPlaylist.playlistType == PlaylistType.UNION) {
                    Button(
                        onClick = viewModel::materializeSelectedUnion,
                        enabled = !state.customPlaylistRefreshInProgress
                    ) {
                        if (state.customPlaylistRefreshInProgress) {
                            Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                            Text(" Refreshing...")
                        } else {
                            Text("Refresh")
                        }
                    }
                    var editSourcesDialogOpen by remember { mutableStateOf(false) }
                    Button(onClick = { editSourcesDialogOpen = true }) { Text("Edit sources") }
                    if (editSourcesDialogOpen) {
                        val localSelections = remember(editSourcesDialogOpen, state.selectedCustomUnionSources) {
                            mutableStateListOf<UnionPlaylistSource>().apply {
                                addAll(state.selectedCustomUnionSources)
                            }
                        }
                        AlertDialog(
                            onDismissRequest = { editSourcesDialogOpen = false },
                            title = { Text("Edit union sources") },
                            text = {
                                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    val providerPlaylists = state.providerPlaylists
                                    val customPlaylists = state.customPlaylists.filter { it.playlistType != PlaylistType.UNION }
                                    val candidates = remember(providerPlaylists, customPlaylists) {
                                        providerPlaylists.map { Triple(it.id, it.name, it.source) } +
                                            customPlaylists.map { Triple(it.id, it.name, SourceType.CUSTOM) }
                                    }

                                    candidates.forEach { (id, name, sourceType) ->
                                        val normalizedCandidateId = normalizeUnionSourcePlaylistId(sourceType, id)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        ) {
                                            val isSelected = localSelections.any {
                                                it.sourceType == sourceType &&
                                                    normalizeUnionSourcePlaylistId(it.sourceType, it.sourcePlaylistId) == normalizedCandidateId
                                            }
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { checked ->
                                                    if (checked) {
                                                        if (!isSelected) {
                                                            localSelections.add(
                                                                UnionPlaylistSource(
                                                                    id = UUID.randomUUID().toString(),
                                                                    unionPlaylistId = selectedCustomPlaylist.id,
                                                                    sourceType = sourceType,
                                                                    sourcePlaylistId = normalizedCandidateId,
                                                                    position = localSelections.size,
                                                                    addedAt = Instant.now().toString()
                                                                )
                                                            )
                                                        }
                                                    } else {
                                                        val updatedSelections = localSelections
                                                            .filterNot {
                                                                it.sourceType == sourceType &&
                                                                    normalizeUnionSourcePlaylistId(it.sourceType, it.sourcePlaylistId) == normalizedCandidateId
                                                            }
                                                            .mapIndexed { idx, s -> s.copy(position = idx) }
                                                        localSelections.clear()
                                                        localSelections.addAll(updatedSelections)
                                                    }
                                                },
                                                modifier = Modifier.padding(end = 8.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                                Text(sourceType.name.lowercase(), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    viewModel.replaceSelectedUnionSources(localSelections.toList())
                                    editSourcesDialogOpen = false
                                }) { Text("Save") }
                            },
                            dismissButton = {
                                OutlinedButton(onClick = { editSourcesDialogOpen = false }) { Text("Cancel") }
                            }
                        )
                    }
                }
            }

            Text("${selectedCustomPlaylist.name} (${selectedCustomPlaylist.playlistType.name.lowercase()})")
            Text("Tracks: ${state.activeCustomPlaylistTracks.size}")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Distinct playback")
                Switch(
                    checked = state.selectedCustomPlaylistIsDistinct,
                    onCheckedChange = viewModel::setSelectedCustomPlaylistDistinct
                )
            }

            if (selectedCustomPlaylist.playlistType == PlaylistType.UNION) {
                RefreshFeedback(
                    inProgress = state.customPlaylistRefreshInProgress,
                    status = state.customPlaylistRefreshStatus,
                    inProgressLabel = "Materializing union..."
                )

                Text("Source playlists")
                if (unionSourceLabels.isEmpty()) {
                    Text("No source playlists added")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        unionSourceLabels.forEachIndexed { index, label ->
                            Text("${index + 1}. $label")
                        }
                    }
                }
            }

            if (state.activeCustomPlaylistTracks.isEmpty()) {
                Text("No tracks found for this playlist")
            } else {
                if (state.selectedCustomPlaylistIsDistinct && state.selectedCustomPlaylistDuplicateGroups.isNotEmpty()) {
                    DuplicateGroupsSection(
                        title = "Duplicates",
                        tracks = state.activeCustomPlaylistTracks,
                        duplicateGroups = state.selectedCustomPlaylistDuplicateGroups,
                        showRemoveControls = selectedCustomPlaylist.playlistType == PlaylistType.STANDARD,
                        onRemoveDuplicate = { duplicateIndex ->
                            pendingStandardDuplicateRemovalIndex = duplicateIndex
                        }
                    )
                }

                val sortedCustomTracks = remember(
                    state.activeCustomPlaylistTracks,
                    customTrackSortColumnName,
                    customTrackSortAscending
                ) {
                    sortTracksWithOriginalIndex(
                        tracks = state.activeCustomPlaylistTracks,
                        sortColumn = customTrackSortColumn,
                        sortAscending = customTrackSortAscending
                    )
                }

                val filteredCustomTracks = remember(sortedCustomTracks, customPlaylistSearchQuery) {
                    if (customPlaylistSearchQuery.isBlank()) sortedCustomTracks
                    else {
                        val q = customPlaylistSearchQuery.lowercase()
                        sortedCustomTracks.filter { row ->
                            row.track.title.lowercase().contains(q) ||
                                row.track.artist.lowercase().contains(q) ||
                                (row.track.album?.lowercase()?.contains(q) == true)
                        }
                    }
                }

                OutlinedTextField(
                    value = customPlaylistSearchQuery,
                    onValueChange = { customPlaylistSearchQuery = it },
                    label = { Text("Search tracks") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                TrackSortableHeaderRow(
                    sortColumn = customTrackSortColumn,
                    sortAscending = customTrackSortAscending,
                    onSort = { column ->
                        if (customTrackSortColumn != column) {
                            customTrackSortColumnName = column.name
                            customTrackSortAscending = true
                        } else if (customTrackSortAscending) {
                            customTrackSortAscending = false
                        } else {
                            customTrackSortColumnName = null
                            customTrackSortAscending = true
                        }
                    }
                )

                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(filteredCustomTracks, key = { _, row -> row.track.id }) { _, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TrackRow(
                                track = row.track,
                                indexLabel = "${row.originalIndex + 1}.",
                                modifier = Modifier.weight(1f)
                            )
                            TrackActionMenu(buildList {
                                add("Play" to { viewModel.playFromCustomPlaylistTrack(selectedCustomPlaylist.id, row.originalIndex) })
                                add("Add to Queue" to { viewModel.addToQueue(row.track) })
                                if (selectedCustomPlaylist.playlistType == PlaylistType.STANDARD) {
                                    add("Remove" to { pendingStandardTrackRemovalIndex = row.originalIndex })
                                }
                            })
                        }
                    }
                }
            }
        }
        pendingStandardDuplicateRemovalIndex?.let { duplicateIndex ->
            AlertDialog(
                onDismissRequest = { pendingStandardDuplicateRemovalIndex = null },
                title = { Text("Remove duplicate?") },
                text = { Text("This removes the selected duplicate occurrence from the playlist.") },
                confirmButton = {
                    Button(onClick = {
                        viewModel.removeTrackFromSelectedCustom(duplicateIndex)
                        pendingStandardDuplicateRemovalIndex = null
                    }) { Text("Remove") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { pendingStandardDuplicateRemovalIndex = null }) { Text("Cancel") }
                }
            )
        }
        pendingStandardTrackRemovalIndex?.let { trackIndex ->
            AlertDialog(
                onDismissRequest = { pendingStandardTrackRemovalIndex = null },
                title = { Text("Remove track?") },
                text = { Text("This removes the track from the playlist.") },
                confirmButton = {
                    Button(onClick = {
                        viewModel.removeTrackFromSelectedCustom(trackIndex)
                        pendingStandardTrackRemovalIndex = null
                    }) { Text("Remove") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { pendingStandardTrackRemovalIndex = null }) { Text("Cancel") }
                }
            )
        }
        return
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Provider playlists")
            Button(
                onClick = viewModel::refreshProviderPlaylistData,
                enabled = !state.providerPlaylistRefreshInProgress,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (state.providerPlaylistRefreshInProgress) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refreshing playlists",
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                } else {
                    Text("Refresh", fontSize = 12.sp)
                }
            }
        }

        if (state.providerPlaylistRefreshInProgress) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refreshing playlists",
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    Text("Refreshing playlists...", style = MaterialTheme.typography.labelSmall)
                }
                state.providerPlaylistRefreshStatus?.let { status ->
                    Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }

        if (state.providerPlaylists.isEmpty()) {
            Text("No provider playlists loaded")
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.providerPlaylists.forEach { playlist ->
                Button(onClick = { viewModel.openProviderPlaylistSummary(playlist) }) {
                    Text("${playlist.name} (${playlist.source.name.lowercase()}) • ${playlist.trackCount}")
                }
            }
        }

        Text("Local custom playlists")
        OutlinedTextField(
            value = newStandardName,
            onValueChange = { newStandardName = it },
            label = { Text("New standard playlist") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            viewModel.createStandardPlaylist(newStandardName)
            newStandardName = ""
        }) { Text("Create Standard") }

        OutlinedTextField(
            value = newUnionName,
            onValueChange = { newUnionName = it },
            label = { Text("New union playlist") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            viewModel.createUnionPlaylist(newUnionName)
            newUnionName = ""
        }) { Text("Create Union") }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.customPlaylists.forEach { playlist ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { viewModel.selectCustomPlaylist(playlist.id) }) {
                        Text("${playlist.name} (${playlist.playlistType.name.lowercase()})")
                    }
                    Button(onClick = { viewModel.playCustomPlaylist(playlist.id) }) { Text("Play") }
                    Button(onClick = { viewModel.deleteCustomPlaylist(playlist.id) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun RefreshFeedback(inProgress: Boolean, status: String?, inProgressLabel: String) {
    if (inProgress) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 2.dp)
                )
                Text(inProgressLabel, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                status ?: "Processing...",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3
            )
        }
    }

    status?.let { refreshStatus ->
        if (!inProgress && refreshStatus.isNotBlank()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val isSuccess = refreshStatus.contains("complete", ignoreCase = true) ||
                    refreshStatus.contains("successfully", ignoreCase = true)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSuccess) Icons.Filled.Check else Icons.Filled.Warning,
                        contentDescription = if (isSuccess) "Success" else "Warning",
                        tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text(refreshStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
