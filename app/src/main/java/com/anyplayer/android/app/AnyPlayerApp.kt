package com.anyplayer.android.app

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.search.SearchType
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.rememberTooltipState
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.DuplicateGroup
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.AlertDialog
import java.util.UUID
import java.time.Instant
import com.anyplayer.android.core.model.UnionPlaylistSource
import com.anyplayer.android.feature.state.transfer.ExportMode
import com.anyplayer.android.feature.state.transfer.MergePolicy
import coil.compose.AsyncImage

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

            Box(modifier = Modifier.weight(1f)) {
                when (currentTab) {
                    TabSection.NOW_PLAYING -> NowPlayingSection(viewModel, state)
                    TabSection.PLAYLISTS -> PlaylistSection(viewModel, state)
                    TabSection.SEARCH -> SearchSection(viewModel, state)
                    TabSection.SETTINGS -> SettingsSection(viewModel, state)
                }
            }
        }
    }
}

@Composable
private fun NowPlayingSection(viewModel: MainViewModel, state: MainUiState) {
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
        status.currentTrack?.imageUrl?.takeIf { it.isNotBlank() }?.let { artworkUrl ->
            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
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
        }

        item {
            Text("Track: ${status.currentTrack?.title ?: "-"}")
        }
        item {
            Text("Artist: ${status.currentTrack?.artist ?: "-"}")
        }
        item {
            Text("State: ${status.state}")
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

@Composable
private fun PlaylistSection(viewModel: MainViewModel, state: MainUiState) {
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
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null
                        )
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

            // Enhanced refresh feedback section
            if (state.providerPlaylistRefreshInProgress) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refreshing",
                            modifier = Modifier.padding(end = 2.dp)
                        )
                        Text(
                            "Refreshing...",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                        )
                    }
                    Text(
                        state.providerPlaylistRefreshStatus ?: "Processing...",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        maxLines = 3
                    )
                }
            }

            // Status message after refresh completes
            state.providerPlaylistRefreshStatus?.let { refreshStatus ->
                if (!state.providerPlaylistRefreshInProgress && refreshStatus.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val isSuccess = refreshStatus.contains("complete", ignoreCase = true) ||
                                       refreshStatus.contains("successfully", ignoreCase = true)
                        if (isSuccess) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Success",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    refreshStatus,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                                )
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = "Warning",
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    refreshStatus,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
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
                            Box {
                                var expanded by remember { mutableStateOf(false) }
                                IconButton(onClick = { expanded = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Track actions")
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    DropdownMenuItem(text = { Text("Add to Queue") }, onClick = { viewModel.addToQueue(row.track); expanded = false })
                                }
                            }
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
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null
                            )
                            Text(" Refreshing...")
                        } else {
                            Text("Refresh")
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    var editSourcesDialogOpen by remember { mutableStateOf(false) }
                    Button(onClick = { editSourcesDialogOpen = true }) { Text("Edit sources") }
                    if (editSourcesDialogOpen) {
                        // Minimal dialog: list non-union provider playlists and custom playlists, allow checking to include.
                        val localSelections = remember(editSourcesDialogOpen, state.selectedCustomUnionSources) {
                            mutableStateListOf<com.anyplayer.android.core.model.UnionPlaylistSource>().apply {
                                addAll(state.selectedCustomUnionSources)
                            }
                        }
                        AlertDialog(
                            onDismissRequest = { editSourcesDialogOpen = false },
                            title = { Text("Edit union sources") },
                            text = {
                                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    // Candidate playlists: provider playlists + custom (non-union) playlists
                                    val providerPlaylists = state.providerPlaylists
                                    val customPlaylists = state.customPlaylists.filter { it.playlistType != PlaylistType.UNION }
                                    val candidates = remember(providerPlaylists, customPlaylists) {
                                        providerPlaylists.map { Triple(it.id, it.name, it.source) } +
                                            customPlaylists.map { Triple(it.id, it.name, SourceType.CUSTOM) }
                                    }

                                    // Compact rows: smaller vertical padding and tighter layout
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
                                                        // add to end
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
                                    // save selections (order not important; keep appended order)
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

            // Enhanced refresh feedback for union playlists
            if (selectedCustomPlaylist.playlistType == PlaylistType.UNION) {
                if (state.customPlaylistRefreshInProgress) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Materializing",
                                modifier = Modifier.padding(end = 2.dp)
                            )
                            Text(
                                "Materializing union...",
                                style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                            )
                        }
                        Text(
                            state.customPlaylistRefreshStatus ?: "Processing...",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                    }
                }

                // Status message after refresh completes
                state.customPlaylistRefreshStatus?.let { refreshStatus ->
                    if (!state.customPlaylistRefreshInProgress && refreshStatus.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val isSuccess = refreshStatus.contains("successfully", ignoreCase = true)
                            if (isSuccess) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "Success",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        refreshStatus,
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                                    )
                                }
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Warning,
                                        contentDescription = "Warning",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        refreshStatus,
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }

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
                            Box {
                                var expanded by remember { mutableStateOf(false) }
                                IconButton(onClick = { expanded = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Track actions")
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Play") },
                                        onClick = { viewModel.playFromCustomPlaylistTrack(selectedCustomPlaylist.id, row.originalIndex); expanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Add to Queue") },
                                        onClick = { viewModel.addToQueue(row.track); expanded = false }
                                    )
                                    if (selectedCustomPlaylist.playlistType == PlaylistType.STANDARD) {
                                        DropdownMenuItem(
                                            text = { Text("Remove") },
                                            onClick = { pendingStandardTrackRemovalIndex = row.originalIndex; expanded = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        pendingStandardDuplicateRemovalIndex?.let { duplicateIndex ->
            AlertDialog(
                onDismissRequest = { pendingStandardDuplicateRemovalIndex = null },
                title = { Text("Remove duplicate?") },
                text = {
                    Text("This removes the selected duplicate occurrence from the playlist.")
                },
                confirmButton = {
                    Button(onClick = {
                        viewModel.removeTrackFromSelectedCustom(duplicateIndex)
                        pendingStandardDuplicateRemovalIndex = null
                    }) {
                        Text("Remove")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { pendingStandardDuplicateRemovalIndex = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
        pendingStandardTrackRemovalIndex?.let { trackIndex ->
            AlertDialog(
                onDismissRequest = { pendingStandardTrackRemovalIndex = null },
                title = { Text("Remove track?") },
                text = {
                    Text("This removes the track from the playlist.")
                },
                confirmButton = {
                    Button(onClick = {
                        viewModel.removeTrackFromSelectedCustom(trackIndex)
                        pendingStandardTrackRemovalIndex = null
                    }) {
                        Text("Remove")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { pendingStandardTrackRemovalIndex = null }) {
                        Text("Cancel")
                    }
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
        
        // Show refresh status when in progress
        if (state.providerPlaylistRefreshInProgress) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
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
                    Text(
                        "Refreshing playlists...",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                    )
                }
                state.providerPlaylistRefreshStatus?.let { status ->
                    Text(
                        status,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        maxLines = 2
                    )
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
private fun SearchSection(viewModel: MainViewModel, state: MainUiState) {
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
                        Box {
                            var expanded by remember { mutableStateOf(false) }
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Track actions")
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("Play") },
                                    onClick = { viewModel.playFromSearch(row.originalIndex); expanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Add to Queue") },
                                    onClick = { viewModel.addToQueue(row.track); expanded = false }
                                )
                                if (state.selectedCustomPlaylistId != null) {
                                    DropdownMenuItem(
                                        text = { Text("Add to Playlist") },
                                        onClick = { viewModel.addSearchTrackToSelectedCustom(row.originalIndex); expanded = false }
                                    )
                                }
                            }
                        }
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

private enum class DataWorkflow { NONE, EXPORT, IMPORT_STATE, IMPORT_CONFIG }
private enum class SettingsTab { GENERAL, SPOTIFY, JELLYFIN, PLEX }
private enum class TrackSortColumn { TITLE, ARTIST, ALBUM, DURATION, SOURCE }
private enum class ProviderSortColumn { PROVIDER, STATUS, TIER, PLAYBACK }

private data class IndexedTrackRow(
    val originalIndex: Int,
    val track: Track
)

@Composable
private fun DuplicateGroupsSection(
    title: String,
    tracks: List<Track>,
    duplicateGroups: List<DuplicateGroup>,
    showRemoveControls: Boolean = false,
    onRemoveDuplicate: ((Int) -> Unit)? = null
) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        duplicateGroups.forEach { group ->
            val keeperTrack = tracks.getOrNull(group.firstOccurrenceIndex)
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Key: ${group.key}")
                    keeperTrack?.let { track ->
                        Text("Kept playback entry:")
                        TrackRow(
                            track = track,
                            indexLabel = "${group.firstOccurrenceIndex + 1}.",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (group.occurrences.isNotEmpty()) {
                        Text("Duplicate occurrences:")
                        group.occurrences.forEach { occurrence ->
                            val duplicateTrack = tracks.getOrNull(occurrence.index) ?: return@forEach
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TrackRow(
                                    track = duplicateTrack,
                                    indexLabel = "${occurrence.index + 1}.",
                                    modifier = Modifier.weight(1f)
                                )
                                if (showRemoveControls && onRemoveDuplicate != null) {
                                    Button(onClick = { onRemoveDuplicate(occurrence.index) }) {
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

private fun sortTracksWithOriginalIndex(
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

@Composable
private fun TrackSortableHeaderRow(
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

@Composable
private fun TrackRow(
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
private fun QueueTableHeader() {
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
private fun QueueTrackRow(
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
        Box {
            var expanded by remember { mutableStateOf(false) }
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Track actions")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Play") },
                    onClick = { onPlay(); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun TrackSourceBadge(source: SourceType, modifier: Modifier = Modifier) {
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
private fun TrackQualityBadge(track: Track, modifier: Modifier = Modifier) {
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

private fun getTrackSourceLabel(source: SourceType): String = when (source) {
    SourceType.SPOTIFY -> "Spotify"
    SourceType.JELLYFIN -> "Jellyfin"
    SourceType.PLEX -> "Plex"
    SourceType.CUSTOM -> "Custom"
    SourceType.ALL -> "All"
}

private fun getTrackQualityLabel(track: Track): String {
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

private fun isSpotifyQualityUnavailable(track: Track): Boolean {
    val hasBitrate = (track.bitrateKbps ?: 0) > 0
    val hasSampleRate = (track.sampleRateHz ?: 0) > 0
    return track.source == SourceType.SPOTIFY && !hasBitrate && !hasSampleRate
}

private fun formatTrackDuration(durationMs: Long?): String {
    val totalSeconds = (durationMs ?: 0L) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun SettingsSection(viewModel: MainViewModel, state: MainUiState) {
    var showJellyToken by rememberSaveable { mutableStateOf(false) }
    var showPlexToken by rememberSaveable { mutableStateOf(false) }
    var activeWorkflow by rememberSaveable { mutableStateOf(DataWorkflow.NONE.name) }
    var activeSettingsTabName by rememberSaveable { mutableStateOf(SettingsTab.GENERAL.name) }
    var showSyncOverwriteConfirm by rememberSaveable { mutableStateOf(false) }
    val workflow = DataWorkflow.entries.firstOrNull { it.name == activeWorkflow } ?: DataWorkflow.NONE
    val activeSettingsTab = SettingsTab.entries.firstOrNull { it.name == activeSettingsTabName } ?: SettingsTab.GENERAL

    val statusBySource = state.providerStatuses.associateBy { it.source }
    val providerStatusRows = listOf(SourceType.JELLYFIN, SourceType.PLEX, SourceType.SPOTIFY).map { source ->
        statusBySource[source] ?: ProviderConnectionProfile(source = source, connected = false)
    }
    val spotifyStatus = providerStatusRows.first { it.source == SourceType.SPOTIFY }
    val jellyfinStatus = providerStatusRows.first { it.source == SourceType.JELLYFIN }
    val plexStatus = providerStatusRows.first { it.source == SourceType.PLEX }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TabRow(selectedTabIndex = activeSettingsTab.ordinal) {
            SettingsTab.entries.forEach { tab ->
                Tab(
                    selected = activeSettingsTab == tab,
                    onClick = { activeSettingsTabName = tab.name },
                    text = {
                        when (tab) {
                            SettingsTab.GENERAL -> Text("General")
                            SettingsTab.SPOTIFY -> SettingsProviderTabLabel(
                                label = "Spotify",
                                connected = spotifyStatus.connected,
                                tooltipText = providerConnectionTooltip(spotifyStatus)
                            )
                            SettingsTab.JELLYFIN -> SettingsProviderTabLabel(
                                label = "Jellyfin",
                                connected = jellyfinStatus.connected,
                                tooltipText = providerConnectionTooltip(jellyfinStatus)
                            )
                            SettingsTab.PLEX -> SettingsProviderTabLabel(
                                label = "Plex",
                                connected = plexStatus.connected,
                                tooltipText = providerConnectionTooltip(plexStatus)
                            )
                        }
                    }
                )
            }
        }

        when (activeSettingsTab) {
            SettingsTab.GENERAL -> {
                Text("Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = state.syncServerTarget,
                    onValueChange = viewModel::updateSyncServerTarget,
                    label = { Text("Sync Server Target") },
                    placeholder = { Text("http://10.0.2.2:8080") },
                    modifier = Modifier.fillMaxWidth()
                )
                var syncAuthTokenVisible by rememberSaveable { mutableStateOf(false) }
                OutlinedTextField(
                    value = state.syncAuthToken,
                    onValueChange = viewModel::updateSyncAuthToken,
                    label = { Text("Sync Auth Token") },
                    placeholder = { Text("Bearer token") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (syncAuthTokenVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { syncAuthTokenVisible = !syncAuthTokenVisible }) {
                            val visible = syncAuthTokenVisible
                            Icon(
                                imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (visible) "Hide token" else "Show token"
                            )
                        }
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.syncAppStateEnabled,
                        onClick = { viewModel.updateSyncAppStateEnabled(!state.syncAppStateEnabled) },
                        label = { Text("app_state") }
                    )
                    FilterChip(
                        selected = state.syncPlaylistsEnabled,
                        onClick = { viewModel.updateSyncPlaylistsEnabled(!state.syncPlaylistsEnabled) },
                        label = { Text("playlists") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.syncProviderConfigurationEnabled,
                        onClick = {
                            viewModel.updateSyncProviderConfigurationEnabled(!state.syncProviderConfigurationEnabled)
                        },
                        label = { Text("provider_configuration") }
                    )
                    FilterChip(
                        selected = state.syncSettingsEnabled,
                        onClick = { viewModel.updateSyncSettingsEnabled(!state.syncSettingsEnabled) },
                        label = { Text("settings") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (state.syncPlaylistsEnabled) {
                                showSyncOverwriteConfirm = true
                            } else {
                                viewModel.pullSyncState(confirmPlaylistOverwrite = false)
                            }
                        }
                    ) {
                        Text("Pull Sync Snapshot")
                    }
                }
                if (state.syncStatus.isNotBlank()) {
                    Text(
                        state.syncStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()
                Text("Connections", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Provider connection controls are available in the Spotify, Jellyfin, and Plex tabs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()
                Text("Playback", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.audioNormalizationEnabled,
                        onClick = { viewModel.setAudioNormalizationEnabled(!state.audioNormalizationEnabled) },
                        label = { Text("Normalize Audio Across Providers") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.audioNormalizationStrictMode,
                        onClick = { viewModel.setAudioNormalizationStrictMode(!state.audioNormalizationStrictMode) },
                        label = { Text("Strict Normalization") }
                    )
                }

                HorizontalDivider()
                Text("Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::clearProviderCaches,
                        enabled = !state.providerConnectionInProgress
                    ) {
                        Text("Clear Provider Cache")
                    }
                }
                if (!state.providerConnectionFeedback.isNullOrBlank()) {
                    Text(
                        state.providerConnectionFeedback,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (workflow == DataWorkflow.NONE) {
                    Text(
                        "Choose what you'd like to do:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkflowCard(
                            title = "Export state",
                            description = "Save your playlists, tracks and settings to a file you can restore later or transfer to another device.",
                            onClick = { activeWorkflow = DataWorkflow.EXPORT.name }
                        )
                        WorkflowCard(
                            title = "Import state",
                            description = "Restore from a previously exported Any Player state file (.json). Optionally preview with a dry run first.",
                            onClick = { activeWorkflow = DataWorkflow.IMPORT_STATE.name }
                        )
                        WorkflowCard(
                            title = "Import config file",
                            description = "Apply a config file from the Any Player companion app. This imports playlists and server URLs without touching auth tokens.",
                            onClick = { activeWorkflow = DataWorkflow.IMPORT_CONFIG.name }
                        )
                    }
                } else {
                    TextButton(onClick = { activeWorkflow = DataWorkflow.NONE.name }) {
                        Text("← Back")
                    }
                    when (workflow) {
                        DataWorkflow.EXPORT       -> ExportWorkflow(viewModel, state)
                        DataWorkflow.IMPORT_STATE -> ImportStateWorkflow(viewModel, state)
                        DataWorkflow.IMPORT_CONFIG -> ImportConfigWorkflow(viewModel, state)
                        DataWorkflow.NONE         -> Unit
                    }
                }

                if (state.stateTransferStatus != "State transfer idle") {
                    HorizontalDivider()
                    Text(
                        state.stateTransferStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SettingsTab.SPOTIFY -> {
                Text("Spotify", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (!state.providerConnectionFeedback.isNullOrBlank()) {
                    Text(state.providerConnectionFeedback, style = MaterialTheme.typography.bodySmall)
                }
                if (state.providerConnectionInProgress) Text("Connecting…", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        if (spotifyStatus.connected) {
                            viewModel.disconnect(SourceType.SPOTIFY)
                        } else {
                            viewModel.beginSpotifyLink()
                        }
                    },
                    enabled = !state.providerConnectionInProgress
                ) {
                    Text(if (spotifyStatus.connected) "Disconnect Spotify" else "Link Spotify Account")
                }
                spotifyStatus.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
                    Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            SettingsTab.JELLYFIN -> {
                Text("Jellyfin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (!state.providerConnectionFeedback.isNullOrBlank()) {
                    Text(state.providerConnectionFeedback, style = MaterialTheme.typography.bodySmall)
                }
                if (state.providerConnectionInProgress) Text("Connecting…", style = MaterialTheme.typography.bodySmall)
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
                                contentDescription = if (showJellyToken) "Hide" else "Show"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.jellyfinPlaylistPageSizeInput,
                    onValueChange = viewModel::updateJellyfinPlaylistPageSizeInput,
                    label = { Text("Playlist Page Size") },
                    placeholder = { Text("300") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        if (state.jellyfinPageSizeSaved) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Saved",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.connectJellyfin(state.jellyfinUrlInput, state.jellyfinTokenInput) },
                        enabled = !state.providerConnectionInProgress
                    ) { Text("Connect Jellyfin") }
                    if (jellyfinStatus.connected) {
                        OutlinedButton(
                            onClick = { viewModel.disconnect(SourceType.JELLYFIN) },
                            enabled = !state.providerConnectionInProgress
                        ) { Text("Disconnect") }
                    }
                }
                jellyfinStatus.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
                    Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            SettingsTab.PLEX -> {
                Text("Plex", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (!state.providerConnectionFeedback.isNullOrBlank()) {
                    Text(state.providerConnectionFeedback, style = MaterialTheme.typography.bodySmall)
                }
                if (state.providerConnectionInProgress) Text("Connecting…", style = MaterialTheme.typography.bodySmall)
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
                                contentDescription = if (showPlexToken) "Hide" else "Show"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.plexPlaylistPageSizeInput,
                    onValueChange = viewModel::updatePlexPlaylistPageSizeInput,
                    label = { Text("Playlist Page Size") },
                    placeholder = { Text("300") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        if (state.plexPageSizeSaved) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Saved",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.connectPlex(state.plexUrlInput, state.plexTokenInput) },
                        enabled = !state.providerConnectionInProgress
                    ) { Text("Connect Plex") }
                    if (plexStatus.connected) {
                        OutlinedButton(
                            onClick = { viewModel.disconnect(SourceType.PLEX) },
                            enabled = !state.providerConnectionInProgress
                        ) { Text("Disconnect") }
                    }
                }
                plexStatus.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
                    Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (showSyncOverwriteConfirm) {
            AlertDialog(
                onDismissRequest = { showSyncOverwriteConfirm = false },
                title = { Text("Confirm playlist overwrite") },
                text = {
                    Text(
                        "Syncing playlists replaces local playlists with server state and may delete local-only playlists. Continue?"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showSyncOverwriteConfirm = false
                            viewModel.pullSyncState(confirmPlaylistOverwrite = true)
                        }
                    ) {
                        Text("Continue")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showSyncOverwriteConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsProviderTabLabel(
    label: String,
    connected: Boolean,
    tooltipText: String
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        if (connected) {
            ProviderCheckmarkTooltip(tooltipText = tooltipText)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCheckmarkTooltip(tooltipText: String) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(tooltipText)
            }
        },
        state = rememberTooltipState()
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = "Connected",
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

private fun providerConnectionTooltip(status: ProviderConnectionProfile): String {
    if (!status.connected) {
        return "Not connected"
    }

    if (status.source == SourceType.SPOTIFY) {
        val tierLabel = when (status.isPremium) {
            true -> "Premium"
            false -> "Free"
            null -> "Tier unknown"
        }
        val playbackLabel = when (status.playbackReady) {
            true -> "Playback ready"
            false -> "Playback setup needed"
            null -> "Playback status unknown"
        }
        return "Connected • $tierLabel • $playbackLabel"
    }

    val serverPart = status.serverUrl?.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""
    return "Connected$serverPart"
}

// ── Workflow sub-screens ──────────────────────────────────────────────────────

@Composable
private fun ExportWorkflow(viewModel: MainViewModel, state: MainUiState) {
    var modeName   by rememberSaveable { mutableStateOf(ExportMode.PORTABLE.name) }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var showPass   by rememberSaveable { mutableStateOf(false) }
    val mode = ExportMode.entries.firstOrNull { it.name == modeName } ?: ExportMode.PORTABLE
    val canExport = mode == ExportMode.PORTABLE || passphrase.isNotBlank()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.exportStateToUri(
            uri = uri,
            mode = mode,
            includePlayback = true,
            passphrase = passphrase.takeIf { it.isNotBlank() }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Export state", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        WorkflowStep(number = 1, label = "Choose export mode") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportMode.entries.forEach { m ->
                    FilterChip(
                        selected = modeName == m.name,
                        onClick  = { modeName = m.name },
                        label    = { Text(m.name.lowercase()) }
                    )
                }
            }
            Text(
                when (mode) {
                    ExportMode.PORTABLE -> "Portable: playlists and server URLs only, no auth tokens. Safe to share."
                    ExportMode.PRIVATE  -> "Private: includes auth tokens. Encrypt with a passphrase."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (mode == ExportMode.PRIVATE) {
            WorkflowStep(number = 2, label = "Set a passphrase") {
                Text(
                    "Required for private mode. You'll need this passphrase to import the file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(
                                imageVector = if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPass) "Hide" else "Show"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        val stepNum = if (mode == ExportMode.PRIVATE) 3 else 2
        WorkflowStep(number = stepNum, label = "Choose save location") {
            Text(
                "Tap the button below to open the file picker and choose where to save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { exportLauncher.launch("any-player-state.json") },
                enabled = canExport,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Choose location & Export") }
        }
    }
}

@Composable
private fun ImportStateWorkflow(viewModel: MainViewModel, state: MainUiState) {
    var policyName by rememberSaveable { mutableStateOf(MergePolicy.MERGE_KEEP_LOCAL.name) }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var showPass   by rememberSaveable { mutableStateOf(false) }
    var selectedUri  by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    val policy = MergePolicy.entries.firstOrNull { it.name == policyName } ?: MergePolicy.MERGE_KEEP_LOCAL
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        selectedUri = uri
        selectedName = context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment ?: "selected file"
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Import state", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        WorkflowStep(number = 1, label = "Choose merge policy") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MergePolicy.entries.forEach { p ->
                    FilterChip(
                        selected = policyName == p.name,
                        onClick  = { policyName = p.name },
                        label    = { Text(p.name.lowercase().replace('_', ' ')) }
                    )
                }
            }
            Text(
                when (policy) {
                    MergePolicy.REPLACE_ALL         -> "Clears all existing playlists and replaces them with the import."
                    MergePolicy.MERGE_KEEP_LOCAL    -> "Adds new items; keeps your local version when there's a conflict."
                    MergePolicy.MERGE_PREFER_IMPORT -> "Adds and updates; imported version wins on conflict."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        WorkflowStep(number = 2, label = "Passphrase (encrypted files only)") {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase (leave blank if not encrypted)") },
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(
                            imageVector = if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPass) "Hide" else "Show"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        WorkflowStep(number = 3, label = "Select file") {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (selectedName != null) "✓ $selectedName" else "Choose file…") }
        }

        Text(
            "Tip: run a dry run first to preview what will change without modifying any data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { selectedUri?.let { uri -> viewModel.importStateFromUri(uri, policy, passphrase.takeIf { p -> p.isNotBlank() }, dryRun = true) } },
                enabled = selectedUri != null,
                modifier = Modifier.weight(1f)
            ) { Text("Dry Run") }
            Button(
                onClick = { selectedUri?.let { uri -> viewModel.importStateFromUri(uri, policy, passphrase.takeIf { p -> p.isNotBlank() }, dryRun = false) } },
                enabled = selectedUri != null,
                modifier = Modifier.weight(1f)
            ) { Text("Import") }
        }
    }
}

@Composable
private fun ImportConfigWorkflow(viewModel: MainViewModel, state: MainUiState) {
    var policyName   by rememberSaveable { mutableStateOf(MergePolicy.MERGE_KEEP_LOCAL.name) }
    var selectedUri  by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    val policy = MergePolicy.entries.firstOrNull { it.name == policyName } ?: MergePolicy.MERGE_KEEP_LOCAL
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        selectedUri = uri
        selectedName = context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment ?: "selected file"
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Import config file", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Config files come from the Any Player companion app. They carry playlists and server URLs — auth tokens are never included, so your existing credentials are preserved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        WorkflowStep(number = 1, label = "Choose merge policy") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MergePolicy.entries.forEach { p ->
                    FilterChip(
                        selected = policyName == p.name,
                        onClick  = { policyName = p.name },
                        label    = { Text(p.name.lowercase().replace('_', ' ')) }
                    )
                }
            }
            Text(
                when (policy) {
                    MergePolicy.REPLACE_ALL         -> "Clears all existing playlists and replaces them with the import."
                    MergePolicy.MERGE_KEEP_LOCAL    -> "Adds new items; keeps your local version when there's a conflict."
                    MergePolicy.MERGE_PREFER_IMPORT -> "Adds and updates; imported version wins on conflict."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        WorkflowStep(number = 2, label = "Select file") {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (selectedName != null) "✓ $selectedName" else "Choose file…") }
        }

        Text(
            "Tip: run a dry run first to preview what will change without modifying any data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { selectedUri?.let { uri -> viewModel.importConfigFromUri(uri, policy, dryRun = true) } },
                enabled = selectedUri != null,
                modifier = Modifier.weight(1f)
            ) { Text("Dry Run") }
            Button(
                onClick = { selectedUri?.let { uri -> viewModel.importConfigFromUri(uri, policy, dryRun = false) } },
                enabled = selectedUri != null,
                modifier = Modifier.weight(1f)
            ) { Text("Import") }
        }
    }
}

// ── Small shared components ───────────────────────────────────────────────────

@Composable
private fun WorkflowCard(title: String, description: String, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkflowStep(number: Int, label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .padding(0.dp)
                    .then(
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    ),
            )
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        }
        content()
    }
}

@Composable
private fun ProviderStatusTable(
    providerStatuses: List<ProviderConnectionProfile>,
    onDisconnect: (SourceType) -> Unit
) {
    var sortColumnName by rememberSaveable("settings_provider_sort_column") { mutableStateOf<String?>(null) }
    var sortAscending by rememberSaveable("settings_provider_sort_ascending") { mutableStateOf(true) }
    val sortColumn = sortColumnName?.let { currentSortName ->
        ProviderSortColumn.entries.firstOrNull { it.name == currentSortName }
    }
    val sortedProviderStatuses = remember(providerStatuses, sortColumnName, sortAscending) {
        if (sortColumn == null) {
            return@remember providerStatuses
        }

        providerStatuses.sortedWith { left, right ->
            val comparison = compareProviderStatuses(left, right, sortColumn)
            if (sortAscending) comparison else -comparison
        }
    }

    fun requestSort(column: ProviderSortColumn) {
        if (sortColumn != column) {
            sortColumnName = column.name
            sortAscending = true
            return
        }

        if (sortAscending) {
            sortAscending = false
            return
        }

        sortColumnName = null
        sortAscending = true
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderSortableHeader(
                    label = "Provider",
                    isActive = sortColumn == ProviderSortColumn.PROVIDER,
                    ascending = sortAscending,
                    modifier = Modifier.weight(1.2f),
                    onClick = { requestSort(ProviderSortColumn.PROVIDER) }
                )
                ProviderSortableHeader(
                    label = "Status",
                    isActive = sortColumn == ProviderSortColumn.STATUS,
                    ascending = sortAscending,
                    modifier = Modifier.weight(1f),
                    onClick = { requestSort(ProviderSortColumn.STATUS) }
                )
                ProviderSortableHeader(
                    label = "Tier",
                    isActive = sortColumn == ProviderSortColumn.TIER,
                    ascending = sortAscending,
                    modifier = Modifier.weight(1f),
                    onClick = { requestSort(ProviderSortColumn.TIER) }
                )
                ProviderSortableHeader(
                    label = "Playback",
                    isActive = sortColumn == ProviderSortColumn.PLAYBACK,
                    ascending = sortAscending,
                    modifier = Modifier.weight(1f),
                    onClick = { requestSort(ProviderSortColumn.PLAYBACK) }
                )
                Text("Action", style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider()

            sortedProviderStatuses.forEachIndexed { index, status ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = providerDisplayName(status.source),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1.2f)
                        )
                        Text(
                            text = if (status.connected) "Connected" else "Disconnected",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = when (status.isPremium) {
                                true -> "Premium"
                                false -> "Free"
                                null -> "—"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = when (status.playbackReady) {
                                true -> "Ready"
                                false -> "Needs Init"
                                null -> "—"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        if (status.connected) {
                            TextButton(onClick = { onDisconnect(status.source) }) {
                                Text("Disconnect")
                            }
                        } else {
                            Text("—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (!status.lastError.isNullOrBlank()) {
                        Text(
                            text = status.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (index != sortedProviderStatuses.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ProviderSortableHeader(
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

private fun providerTierRank(status: ProviderConnectionProfile): Int = when (status.isPremium) {
    true -> 2
    false -> 1
    null -> 0
}

private fun providerPlaybackRank(status: ProviderConnectionProfile): Int = when (status.playbackReady) {
    true -> 2
    false -> 1
    null -> 0
}

private fun compareProviderStatuses(
    left: ProviderConnectionProfile,
    right: ProviderConnectionProfile,
    sortColumn: ProviderSortColumn
): Int = when (sortColumn) {
    ProviderSortColumn.PROVIDER -> providerDisplayName(left.source).compareTo(providerDisplayName(right.source), ignoreCase = true)
    ProviderSortColumn.STATUS -> (if (left.connected) 1 else 0).compareTo(if (right.connected) 1 else 0)
    ProviderSortColumn.TIER -> providerTierRank(left).compareTo(providerTierRank(right))
    ProviderSortColumn.PLAYBACK -> providerPlaybackRank(left).compareTo(providerPlaybackRank(right))
}

private fun providerDisplayName(sourceType: SourceType): String = when (sourceType) {
    SourceType.JELLYFIN -> "Jellyfin"
    SourceType.PLEX -> "Plex"
    SourceType.SPOTIFY -> "Spotify"
    SourceType.CUSTOM -> "Custom"
    SourceType.ALL -> "All"
}

private fun normalizeUnionSourcePlaylistId(sourceType: SourceType, sourcePlaylistId: String): String {
    if (sourceType != SourceType.SPOTIFY) return sourcePlaylistId.trim()
    return sourcePlaylistId
        .trim()
        .substringAfter("spotify:playlist:", sourcePlaylistId.trim())
        .let { value ->
            value.substringAfter("/playlist/", value)
                .substringBefore('?')
                .substringBefore('/')
        }
}
