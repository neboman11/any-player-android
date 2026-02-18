package com.anyplayer.android.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.feature.search.SearchType
import com.anyplayer.android.feature.state.transfer.ExportMode
import com.anyplayer.android.feature.state.transfer.MergePolicy

private enum class TabSection { NOW_PLAYING, PLAYLISTS, SEARCH, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnyPlayerApp(viewModel: MainViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val redirectUri by SpotifyAuthRedirectBus.redirectUri.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var currentTab by remember { mutableStateOf(TabSection.NOW_PLAYING) }

    LaunchedEffect(state.spotifyAuthLaunchUrl) {
        val url = state.spotifyAuthLaunchUrl ?: return@LaunchedEffect
        uriHandler.openUri(url)
        viewModel.markSpotifyAuthLaunchHandled()
    }

    LaunchedEffect(redirectUri) {
        val callback = redirectUri ?: return@LaunchedEffect
        viewModel.completeSpotifyLink(callback)
        SpotifyAuthRedirectBus.clear()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Any Player") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = state.startupMessage, style = MaterialTheme.typography.bodyMedium)
            if (state.startupWarnings.isNotEmpty()) {
                state.startupWarnings.forEach { warning ->
                    Text(text = warning, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.startupCanRetry || state.startupCanContinueWithoutProvider) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.startupCanRetry) {
                        Button(onClick = viewModel::retryStartup, enabled = !state.startupInProgress) {
                            Text("Retry Startup")
                        }
                    }
                    if (state.startupCanContinueWithoutProvider) {
                        Button(onClick = viewModel::continueWithoutProviderStartup, enabled = !state.startupInProgress) {
                            Text("Continue Without Provider")
                        }
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TabSection.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        shape = SegmentedButtonDefaults.itemShape(index, TabSection.entries.size)
                    ) {
                        Text(
                            when (tab) {
                                TabSection.NOW_PLAYING -> "Now Playing"
                                TabSection.PLAYLISTS -> "Playlists"
                                TabSection.SEARCH -> "Search"
                                TabSection.SETTINGS -> "Settings"
                            }
                        )
                    }
                }
            }

            when (currentTab) {
                TabSection.NOW_PLAYING -> NowPlayingSection(viewModel, state)
                TabSection.PLAYLISTS -> PlaylistSection(viewModel, state)
                TabSection.SEARCH -> SearchSection(viewModel, state)
                TabSection.SETTINGS -> SettingsSection(viewModel, state)
            }
        }
    }
}

@Composable
private fun NowPlayingSection(viewModel: MainViewModel, state: MainUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Track: ${state.playbackStatus.currentTrack?.title ?: "-"}")
        Text("Artist: ${state.playbackStatus.currentTrack?.artist ?: "-"}")
        Text("State: ${state.playbackStatus.state}")
        state.playbackStatus.errorMessage?.let { err ->
            Text(
                text = "Error: $err",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::previous) { Text("Previous") }
            Button(onClick = viewModel::togglePlayPause) { Text("Play/Pause") }
            Button(onClick = viewModel::next) { Text("Next") }
        }

        Slider(
            value = state.playbackStatus.position.toFloat(),
            valueRange = 0f..(state.playbackStatus.duration.takeIf { it > 0 } ?: 1L).toFloat(),
            onValueChange = { viewModel.seekTo(it.toLong()) }
        )
        Slider(
            value = state.playbackStatus.volume.toFloat(),
            valueRange = 0f..100f,
            onValueChange = { viewModel.setVolume(it.toInt()) }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.playbackStatus.shuffle,
                onClick = { viewModel.setShuffle(!state.playbackStatus.shuffle) },
                label = { Text("Shuffle") }
            )
            RepeatMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.playbackStatus.repeatMode == mode,
                    onClick = { viewModel.setRepeatMode(mode) },
                    label = { Text(mode.name) }
                )
            }
        }

        Text("Queue")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(state.playbackStatus.queue) { index, track ->
                Button(onClick = { viewModel.playFromQueue(index) }) {
                    Text("${index + 1}. ${track.title} • ${track.artist}")
                }
            }
        }
    }
}

@Composable
private fun PlaylistSection(viewModel: MainViewModel, state: MainUiState) {
    var newStandardName by remember { mutableStateOf("") }
    var newUnionName by remember { mutableStateOf("") }

    val selectedProviderPlaylist = state.selectedProviderPlaylist

    if (selectedProviderPlaylist != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::closeProviderPlaylistSummary) { Text("Back") }
                Button(onClick = viewModel::playSelectedProviderPlaylist) { Text("Play Playlist") }
                Button(
                    onClick = viewModel::refreshProviderPlaylistData,
                    enabled = !state.providerPlaylistRefreshInProgress
                ) { Text("Refresh") }
            }

            Text("${selectedProviderPlaylist.name} (${selectedProviderPlaylist.source.name.lowercase()})")
            Text("Tracks: ${state.selectedProviderPlaylistTracks.size}")

            if (state.providerPlaylistRefreshInProgress) {
                Text("Refreshing playlist data...")
            }
            state.providerPlaylistRefreshStatus?.let { refreshStatus ->
                Text(refreshStatus)
            }

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
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(state.selectedProviderPlaylistTracks) { index, track ->
                        Text("${index + 1}. ${track.title} • ${track.artist}")
                    }
                }
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Provider playlists")
        if (state.providerPlaylists.isEmpty()) {
            Text("No provider playlists loaded")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(state.providerPlaylists) { _, playlist ->
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

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(state.customPlaylists) { _, playlist ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { viewModel.selectCustomPlaylist(playlist.id) }) {
                        Text("${playlist.name} (${playlist.playlistType.name.lowercase()})")
                    }
                    Button(onClick = { viewModel.playCustomPlaylist(playlist.id) }) { Text("Play") }
                    Button(onClick = { viewModel.deleteCustomPlaylist(playlist.id) }) { Text("Delete") }
                }

                if (state.selectedCustomPlaylistId == playlist.id) {
                    if (playlist.playlistType == PlaylistType.UNION) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.providerPlaylists.take(3).forEach { providerPlaylist ->
                                Button(onClick = {
                                    viewModel.addProviderPlaylistSourceToSelectedUnion(
                                        sourcePlaylistId = providerPlaylist.id,
                                        sourceType = providerPlaylist.source
                                    )
                                }) {
                                    Text("+ ${providerPlaylist.name.take(10)}")
                                }
                            }
                        }
                    }

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(state.activeCustomPlaylistTracks) { index, track ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = {
                                    viewModel.playFromCustomPlaylistTrack(playlist.id, index)
                                }) {
                                    Text("${index + 1}. ${track.title}")
                                }
                                if (playlist.playlistType == PlaylistType.STANDARD) {
                                    Button(onClick = { viewModel.removeTrackFromSelectedCustom(index) }) {
                                        Text("Remove")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSection(viewModel: MainViewModel, state: MainUiState) {
    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(SourceType.ALL) }
    var searchType by remember { mutableStateOf(SearchType.TRACKS) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search tracks or playlists") }
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

        Button(onClick = { viewModel.search(query, source, searchType) }) {
            Text("Search")
        }

        if (searchType == SearchType.TRACKS) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(state.searchResults) { index, track ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { viewModel.playFromSearch(index) }) {
                            Text("${track.title} • ${track.artist} (${track.source.name.lowercase()})")
                        }
                        if (state.selectedCustomPlaylistId != null) {
                            Button(onClick = { viewModel.addSearchTrackToSelectedCustom(index) }) {
                                Text("Add")
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(state.searchPlaylistResults) { index, playlist ->
                    Button(onClick = { viewModel.playPlaylistFromSearch(index) }) {
                        Text("${playlist.name} (${playlist.source.name.lowercase()}) • ${playlist.trackCount}")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(viewModel: MainViewModel, state: MainUiState) {
    var showJellyToken by rememberSaveable { mutableStateOf(false) }
    var showPlexToken by rememberSaveable { mutableStateOf(false) }
    var exportPath by rememberSaveable { mutableStateOf("/sdcard/Download/any-player-state.json") }
    var importPath by rememberSaveable { mutableStateOf("/sdcard/Download/any-player-state.json") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var exportModeName by rememberSaveable { mutableStateOf(ExportMode.PORTABLE.name) }
    var mergePolicyName by rememberSaveable { mutableStateOf(MergePolicy.MERGE_KEEP_LOCAL.name) }
    val exportMode = ExportMode.entries.firstOrNull { it.name == exportModeName } ?: ExportMode.PORTABLE
    val mergePolicy = MergePolicy.entries.firstOrNull { it.name == mergePolicyName } ?: MergePolicy.MERGE_KEEP_LOCAL
    val jellyConnected = state.providerStatuses.any { it.source == SourceType.JELLYFIN && it.connected }
    val plexConnected = state.providerStatuses.any { it.source == SourceType.PLEX && it.connected }
    val spotifyConnected = state.providerStatuses.any { it.source == SourceType.SPOTIFY && it.connected }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Provider Connections")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Jellyfin: ${if (jellyConnected) "Connected" else "Not connected"}")
            Text("Plex: ${if (plexConnected) "Connected" else "Not connected"}")
            Text("Spotify: ${if (spotifyConnected) "Connected" else "Not connected"}")
        }
        if (!state.providerConnectionFeedback.isNullOrBlank()) {
            Text(state.providerConnectionFeedback)
        }
        if (state.providerConnectionInProgress) {
            Text("Connecting...")
        }

        OutlinedTextField(
            value = state.jellyfinUrlInput,
            onValueChange = viewModel::updateJellyfinUrlInput,
            label = { Text("Jellyfin URL") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.jellyfinTokenInput,
            onValueChange = viewModel::updateJellyfinTokenInput,
            label = { Text("Jellyfin API Key") },
            visualTransformation = if (showJellyToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showJellyToken = !showJellyToken }) {
                    Icon(
                        imageVector = if (showJellyToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showJellyToken) "Hide Jellyfin API key" else "Show Jellyfin API key"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { viewModel.connectJellyfin(state.jellyfinUrlInput, state.jellyfinTokenInput) },
            enabled = !state.providerConnectionInProgress
        ) { Text("Connect Jellyfin") }

        OutlinedTextField(
            value = state.plexUrlInput,
            onValueChange = viewModel::updatePlexUrlInput,
            label = { Text("Plex URL") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.plexTokenInput,
            onValueChange = viewModel::updatePlexTokenInput,
            label = { Text("Plex Token") },
            visualTransformation = if (showPlexToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPlexToken = !showPlexToken }) {
                    Icon(
                        imageVector = if (showPlexToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showPlexToken) "Hide Plex token" else "Show Plex token"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { viewModel.connectPlex(state.plexUrlInput, state.plexTokenInput) },
            enabled = !state.providerConnectionInProgress
        ) { Text("Connect Plex") }

        Button(
            onClick = viewModel::beginSpotifyLink,
            enabled = !state.providerConnectionInProgress
        ) {
            Text("Link Spotify Account")
        }

        state.providerStatuses.forEach { status ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${status.source.name.lowercase()}: ${if (status.connected) "connected" else "disconnected"}")
                if (status.connected) {
                    Button(onClick = { viewModel.disconnect(status.source) }) { Text("Disconnect") }
                }
            }
            if (!status.lastError.isNullOrBlank()) {
                Text(status.lastError)
            }
        }

        Text("State Transfer")
        OutlinedTextField(value = exportPath, onValueChange = { exportPath = it }, label = { Text("Export file path") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = importPath, onValueChange = { importPath = it }, label = { Text("Import file path") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = passphrase, onValueChange = { passphrase = it }, label = { Text("Passphrase (private mode)") }, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExportMode.entries.forEach { mode ->
                FilterChip(
                    selected = exportModeName == mode.name,
                    onClick = { exportModeName = mode.name },
                    label = { Text(mode.name.lowercase()) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MergePolicy.entries.forEach { policy ->
                FilterChip(
                    selected = mergePolicyName == policy.name,
                    onClick = { mergePolicyName = policy.name },
                    label = { Text(policy.name.lowercase()) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                viewModel.exportState(
                    path = exportPath,
                    mode = exportMode,
                    includePlayback = true,
                    passphrase = passphrase
                )
            }) { Text("Export State") }
            Button(onClick = { viewModel.dryRunImport(importPath, mergePolicy, passphrase) }) { Text("Dry Run") }
            Button(onClick = { viewModel.importState(importPath, mergePolicy, passphrase) }) { Text("Import State") }
        }

        Text(state.stateTransferStatus)
    }
}
