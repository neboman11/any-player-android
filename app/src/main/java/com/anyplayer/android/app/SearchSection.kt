package com.anyplayer.android.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.feature.search.SearchType

@Composable
internal fun SearchSection(viewModel: MainViewModel, state: MainUiState) {
    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(SourceType.ALL) }
    var searchType by remember { mutableStateOf(SearchType.TRACKS) }
    var searchTrackSortColumnName by rememberSaveable("search_track_sort_column") { mutableStateOf<String?>(null) }
    var searchTrackSortAscending by rememberSaveable("search_track_sort_ascending") { mutableStateOf(true) }
    val searchTrackSortColumn = searchTrackSortColumnName?.let { sortName ->
        TrackSortColumn.entries.firstOrNull { it.name == sortName }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search tracks or playlists") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search(query, source, searchType) }),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceType.entries.forEach { sourceType ->
                if (sourceType == SourceType.CUSTOM) return@forEach
                FilterChip(
                    selected = source == sourceType,
                    onClick = {
                        source = sourceType
                        viewModel.search(query, sourceType, searchType)
                    },
                    label = { Text(sourceType.name.lowercase()) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchType.entries.forEach { type ->
                FilterChip(
                    selected = searchType == type,
                    onClick = {
                        searchType = type
                        viewModel.search(query, source, type)
                    },
                    label = { Text(type.name.lowercase()) }
                )
            }
        }

        OutlinedButton(onClick = { viewModel.search(query, source, searchType) }) {
            Text("Search")
        }

        if (searchType == SearchType.TRACKS) {
            val sortedSearchTracks = remember(state.searchResults, searchTrackSortColumnName, searchTrackSortAscending) {
                sortTracksWithOriginalIndex(
                    tracks = state.searchResults,
                    sortColumn = searchTrackSortColumn,
                    sortAscending = searchTrackSortAscending
                )
            }

            TrackSortableHeaderRow(
                sortColumn = searchTrackSortColumn,
                sortAscending = searchTrackSortAscending,
                onSort = { column ->
                    if (searchTrackSortColumn != column) {
                        searchTrackSortColumnName = column.name
                        searchTrackSortAscending = true
                    } else if (searchTrackSortAscending) {
                        searchTrackSortAscending = false
                    } else {
                        searchTrackSortColumnName = null
                        searchTrackSortAscending = true
                    }
                }
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(sortedSearchTracks, key = { _, row -> row.track.id }) { _, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TrackRow(track = row.track, indexLabel = "${row.originalIndex + 1}.", modifier = Modifier.weight(1f))
                        TrackActionMenu(buildList {
                            add("Play" to { viewModel.playFromSearch(row.originalIndex) })
                            add("Add to Queue" to { viewModel.addToQueue(row.track) })
                            if (state.selectedCustomPlaylistId != null) {
                                add("Add to Playlist" to { viewModel.addSearchTrackToSelectedCustom(row.originalIndex) })
                            }
                        })
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(state.searchPlaylistResults, key = { _, playlist -> "${playlist.source}_${playlist.id}" }) { index, playlist ->
                    Button(onClick = { viewModel.playPlaylistFromSearch(index) }) {
                        Text("${playlist.name} (${playlist.source.name.lowercase()}) • ${playlist.trackCount}")
                    }
                }
            }
        }
    }
}
