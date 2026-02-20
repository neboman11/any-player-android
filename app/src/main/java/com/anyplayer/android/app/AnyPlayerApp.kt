package com.anyplayer.android.app

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
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
    val currentTrackId = status.currentTrack?.id
    val displayQueue = status.orderedQueue.ifEmpty { status.queue }
    val originalQueue = status.queue

    val currentIdx = displayQueue.indexOfFirst { it.id == currentTrackId }
    val upcomingTracks = if (currentIdx >= 0) displayQueue.drop(currentIdx + 1) else displayQueue
    val pastTracks    = if (currentIdx >  0) displayQueue.take(currentIdx)     else emptyList()

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Track: ${status.currentTrack?.title ?: "-"}")
        Text("Artist: ${status.currentTrack?.artist ?: "-"}")
        Text("State: ${status.state}")
        status.errorMessage?.let { err ->
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
            value = status.position.toFloat(),
            valueRange = 0f..(status.duration.takeIf { it > 0 } ?: 1L).toFloat(),
            onValueChange = { viewModel.seekTo(it.toLong()) }
        )
        Slider(
            value = status.volume.toFloat(),
            valueRange = 0f..100f,
            onValueChange = { viewModel.setVolume(it.toInt()) }
        )

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

        if (upcomingTracks.isNotEmpty() || pastTracks.isNotEmpty()) {
            Text("Up Next")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                upcomingTracks.forEach { track ->
                    Button(
                        onClick = {
                            val originalIdx = originalQueue.indexOfFirst { it.id == track.id }
                            if (originalIdx >= 0) viewModel.playFromQueue(originalIdx)
                        }
                    ) {
                        Text("${track.title} • ${track.artist}")
                    }
                }
            }

            if (pastTracks.isNotEmpty()) {
                Text(
                    "${pastTracks.size} track${if (pastTracks.size == 1) "" else "s"} already played",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    pastTracks.forEach { track ->
                        Button(
                            onClick = {
                                val originalIdx = originalQueue.indexOfFirst { it.id == track.id }
                                if (originalIdx >= 0) viewModel.playFromQueue(originalIdx)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text("${track.title} • ${track.artist}")
                        }
                    }
                }
            }
        } else if (displayQueue.isEmpty()) {
            Text("No queue loaded", style = MaterialTheme.typography.bodySmall)
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

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Provider playlists")
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

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.activeCustomPlaylistTracks.forEachIndexed { index, track ->
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

private enum class DataWorkflow { NONE, EXPORT, IMPORT_STATE, IMPORT_CONFIG }

@Composable
private fun SettingsSection(viewModel: MainViewModel, state: MainUiState) {
    var showJellyToken by rememberSaveable { mutableStateOf(false) }
    var showPlexToken by rememberSaveable { mutableStateOf(false) }
    var activeWorkflow by rememberSaveable { mutableStateOf(DataWorkflow.NONE.name) }
    val workflow = DataWorkflow.entries.firstOrNull { it.name == activeWorkflow } ?: DataWorkflow.NONE

    val jellyConnected = state.providerStatuses.any { it.source == SourceType.JELLYFIN && it.connected }
    val plexConnected  = state.providerStatuses.any { it.source == SourceType.PLEX       && it.connected }
    val spotifyConnected = state.providerStatuses.any { it.source == SourceType.SPOTIFY  && it.connected }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Provider connections ──────────────────────────────────────────────
        Text("Connections", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderBadge("Jellyfin",  jellyConnected)
            ProviderBadge("Plex",      plexConnected)
            ProviderBadge("Spotify",   spotifyConnected)
        }

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
                        contentDescription = if (showPlexToken) "Hide" else "Show"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { viewModel.connectPlex(state.plexUrlInput, state.plexTokenInput) },
            enabled = !state.providerConnectionInProgress
        ) { Text("Connect Plex") }

        Button(onClick = viewModel::beginSpotifyLink, enabled = !state.providerConnectionInProgress) {
            Text("Link Spotify Account")
        }

        state.providerStatuses.forEach { status ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${status.source.name.lowercase()}: ${if (status.connected) "connected" else "disconnected"}")
                if (status.connected) {
                    Button(onClick = { viewModel.disconnect(status.source) }) { Text("Disconnect") }
                }
            }
            if (!status.lastError.isNullOrBlank()) Text(status.lastError, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()

        // ── Data workflows ────────────────────────────────────────────────────
        Text("Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        if (workflow == DataWorkflow.NONE) {
            // Landing — pick a workflow
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

        // Status line shown for all workflows
        if (state.stateTransferStatus != "State transfer idle") {
            HorizontalDivider()
            Text(
                state.stateTransferStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
private fun ProviderBadge(name: String, connected: Boolean) {
    val containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (connected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Text(
            text = if (connected) "$name ✓" else name,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

