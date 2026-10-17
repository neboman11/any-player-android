package com.anyplayer.android.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anyplayer.android.core.model.SearchType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.ui.components.AddToPlaylistDialog
import com.anyplayer.android.ui.components.Artwork
import com.anyplayer.android.ui.components.MessagesEffect
import com.anyplayer.android.ui.components.PlaylistChoice
import com.anyplayer.android.ui.components.ScreenPadding
import com.anyplayer.android.ui.components.SourceBadge
import com.anyplayer.android.ui.components.TrackRowAction
import com.anyplayer.android.ui.components.TrackSortColumn
import com.anyplayer.android.ui.components.TrackTable

private val SearchRowActions = listOf(TrackRowAction.PLAY, TrackRowAction.ADD_TO_QUEUE, TrackRowAction.ADD_TO_PLAYLIST)

@Composable
fun SearchRoute(viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    MessagesEffect(viewModel.messages)
    SearchScreen(
        state = state,
        query = query,
        onQueryChange = viewModel::setQuery,
        onSubmit = viewModel::submit,
        onSourceChange = viewModel::setSource,
        onTypeChange = viewModel::setType,
        onSortClick = viewModel::toggleSort,
        onPlayTrack = viewModel::playTrack,
        onAddToQueue = viewModel::addToQueue,
        onAddToPlaylist = viewModel::addToPlaylist,
        onPlayPlaylist = viewModel::playPlaylist,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    state: SearchUi,
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSourceChange: (SourceType) -> Unit,
    onTypeChange: (SearchType) -> Unit,
    onSortClick: (TrackSortColumn) -> Unit,
    onPlayTrack: (Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (PlaylistChoice, Track) -> Unit,
    onPlayPlaylist: (PlaylistResultUi) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        onSubmit()
    }
    var pickerTrack by remember { mutableStateOf<Track?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = ScreenPadding).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Search tracks or playlists") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = submit, modifier = Modifier.testTag("search_submit_button").padding(top = 6.dp)) {
                    Text("Search")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SearchSources.forEach { source ->
                    FilterChip(
                        selected = state.source == source,
                        onClick = { onSourceChange(source) },
                        label = { Text(source.name.lowercase()) },
                    )
                }
                Spacer(Modifier.width(8.dp))
                SearchType.entries.forEach { type ->
                    FilterChip(
                        selected = state.type == type,
                        onClick = { onTypeChange(type) },
                        label = { Text(type.name.lowercase()) },
                    )
                }
            }
            if (state.phase == SearchPhase.LOADING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when (state.phase) {
                SearchPhase.EMPTY -> Text("No results", style = MaterialTheme.typography.bodyMedium)
                SearchPhase.ERROR -> Text(
                    "Search failed: ${state.error.orEmpty()}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                SearchPhase.IDLE, SearchPhase.LOADING, SearchPhase.RESULTS -> Unit
            }
        }

        if (state.type == SearchType.TRACKS) {
            TrackTable(
                table = state.table,
                actions = SearchRowActions,
                onAction = { action, row ->
                    when (action) {
                        TrackRowAction.PLAY -> onPlayTrack(row.originalIndex)
                        TrackRowAction.ADD_TO_QUEUE -> onAddToQueue(row.track)
                        TrackRowAction.ADD_TO_PLAYLIST -> pickerTrack = row.track
                        TrackRowAction.REMOVE -> Unit
                    }
                },
                onSortClick = onSortClick,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.playlists, key = { it.key }) { playlist ->
                    ElevatedCard(onClick = { onPlayPlaylist(playlist) }, modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            leadingContent = { Artwork(playlist.imageUrl, 48.dp) },
                            headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SourceBadge(playlist.source)
                                    Spacer(Modifier.width(8.dp))
                                    Text("${playlist.trackCount} tracks", style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            trailingContent = { Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${playlist.name}") },
                        )
                    }
                }
            }
        }
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
