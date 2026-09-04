package com.anyplayer.android.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.anyplayer.android.app.MainUiState
import com.anyplayer.android.app.MainViewModel
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.feature.djfiller.model.DjModelDownloadState
import com.anyplayer.android.feature.state.transfer.ExportMode
import com.anyplayer.android.feature.state.transfer.MergePolicy

private enum class DataWorkflow { NONE, EXPORT, IMPORT_STATE, IMPORT_CONFIG }
private enum class SettingsTab { GENERAL, SPOTIFY, JELLYFIN, PLEX }

@Composable
internal fun SettingsSection(viewModel: MainViewModel, state: MainUiState) {
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
                    modifier = Modifier.testTag("settings_tab_${tab.name}"),
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
                SecretTextField(
                    value = state.syncAuthToken,
                    onValueChange = viewModel::updateSyncAuthToken,
                    label = "Sync Auth Token",
                    modifier = Modifier.fillMaxWidth()
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
                    Button(onClick = viewModel::connectToSyncServer) {
                        Text("Connect")
                    }
                    OutlinedButton(
                        onClick = {
                            if (state.syncPlaylistsEnabled) {
                                showSyncOverwriteConfirm = true
                            } else {
                                viewModel.pullSyncState(confirmPlaylistOverwrite = false)
                            }
                        }
                    ) {
                        Text("Force Pull from Server")
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
                enabled = false,
                selected = state.audioNormalizationStrictMode,
                        onClick = { viewModel.setAudioNormalizationStrictMode(!state.audioNormalizationStrictMode) },
                label = { Text("Strict Normalization (Unavailable)") }
                    )
                }

                HorizontalDivider()
                Text("AI DJ (Beta)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Every few songs, an on-device AI DJ introduces what's coming up next. " +
                        "Text generation and speech happen entirely on this device; only a " +
                        "short fact about the artist is looked up online.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.aiDjEnabled,
                        onClick = { viewModel.setAiDjEnabled(!state.aiDjEnabled) },
                        label = { Text("Enable AI DJ") }
                    )
                }
                if (state.aiDjEnabled && state.aiDjModelDownloadState !is DjModelDownloadState.Ready) {
                    WorkflowStep(number = 1, label = "Download AI DJ voice model") {
                        when (val downloadState = state.aiDjModelDownloadState) {
                            is DjModelDownloadState.Downloading -> Text(
                                "Downloading... ${(downloadState.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                            is DjModelDownloadState.Failed -> Column {
                                Text(
                                    downloadState.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Button(onClick = viewModel::downloadDjModel) { Text("Retry Download") }
                            }
                            else -> Button(onClick = viewModel::downloadDjModel) { Text("Download") }
                        }
                    }
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
                    Text("Choose what you'd like to do:", style = MaterialTheme.typography.bodyMedium)
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
                        DataWorkflow.EXPORT       -> ExportWorkflow(viewModel)
                        DataWorkflow.IMPORT_STATE -> ImportStateWorkflow(viewModel)
                        DataWorkflow.IMPORT_CONFIG -> ImportConfigWorkflow(viewModel)
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
                ProviderConnectionHeader(
                    name = "Spotify",
                    feedback = state.providerConnectionFeedback,
                    connecting = state.providerConnectionInProgress
                )
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
                ProviderConnectionHeader(
                    name = "Jellyfin",
                    feedback = state.providerConnectionFeedback,
                    connecting = state.providerConnectionInProgress
                )
                OutlinedTextField(
                    value = state.jellyfinUrlInput,
                    onValueChange = viewModel::updateJellyfinUrlInput,
                    label = { Text("Jellyfin URL") },
                    modifier = Modifier.fillMaxWidth().testTag("field_jellyfin_url")
                )
                SecretTextField(
                    value = state.jellyfinTokenInput,
                    onValueChange = viewModel::updateJellyfinTokenInput,
                    label = "Jellyfin API Key",
                    modifier = Modifier.fillMaxWidth().testTag("field_jellyfin_token")
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
                ProviderConnectionHeader(
                    name = "Plex",
                    feedback = state.providerConnectionFeedback,
                    connecting = state.providerConnectionInProgress
                )
                OutlinedTextField(
                    value = state.plexUrlInput,
                    onValueChange = viewModel::updatePlexUrlInput,
                    label = { Text("Plex URL") },
                    modifier = Modifier.fillMaxWidth().testTag("field_plex_url")
                )
                SecretTextField(
                    value = state.plexTokenInput,
                    onValueChange = viewModel::updatePlexTokenInput,
                    label = "Plex Token",
                    modifier = Modifier.fillMaxWidth().testTag("field_plex_token")
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

        if (state.syncConflictPending) {
            AlertDialog(
                onDismissRequest = viewModel::dismissSyncConflict,
                title = { Text("Sync server already has data") },
                text = {
                    Text(
                        "This server already has synced data for at least one enabled domain. " +
                            "Keep this device's data (overwrites the server) or take the server's " +
                            "data (overwrites this device)?"
                    )
                },
                confirmButton = {
                    Button(onClick = { viewModel.resolveSyncConflict(useLocal = true) }) {
                        Text("Keep This Device")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { viewModel.resolveSyncConflict(useLocal = false) }) {
                        Text("Use Server Data")
                    }
                }
            )
        }
    }
}

@Composable
private fun ExportWorkflow(viewModel: MainViewModel) {
    var modeName   by rememberSaveable { mutableStateOf(ExportMode.PORTABLE.name) }
    var passphrase by rememberSaveable { mutableStateOf("") }
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
                SecretTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = "Passphrase",
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
private fun ImportStateWorkflow(viewModel: MainViewModel) {
    var policyName by rememberSaveable { mutableStateOf(MergePolicy.MERGE_KEEP_LOCAL.name) }
    var passphrase by rememberSaveable { mutableStateOf("") }
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
            SecretTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = "Passphrase (leave blank if not encrypted)",
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
private fun ImportConfigWorkflow(viewModel: MainViewModel) {
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
