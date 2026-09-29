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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import com.anyplayer.android.app.MainUiState
import com.anyplayer.android.app.MainViewModel
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.feature.djfiller.DjVoiceState
import com.anyplayer.android.feature.djfiller.DjScriptModelState
import com.anyplayer.android.feature.djfiller.model.DjModelDownloadState
import com.anyplayer.android.feature.state.transfer.ExportMode
import com.anyplayer.android.feature.state.transfer.MergePolicy

private enum class DataWorkflow { NONE, EXPORT, IMPORT_STATE, IMPORT_CONFIG }
private enum class SettingsTab { GENERAL, PROVIDERS, AI_DJ }

internal data class DjCatalogOption(val id: String, val displayName: String)

/** Picker state shared by the AI DJ script-model and voice catalogs. */
internal data class DjCatalogSettingsUiState(
    val options: List<DjCatalogOption>,
    val selectedName: String?,
    val activeLabel: String,
    val canDownload: Boolean
)

internal fun djVoiceSettingsUiState(
    voiceState: DjVoiceState,
    downloadState: DjModelDownloadState = DjModelDownloadState.NotDownloaded
): DjCatalogSettingsUiState = djCatalogSettingsUiState(
    options = voiceState.catalog?.voices.orEmpty().map { DjCatalogOption(it.id, it.name) },
    selectedId = voiceState.selectedId,
    activeId = voiceState.activeVoice?.id,
    downloadState = downloadState
)

internal fun djModelSettingsUiState(
    modelState: DjScriptModelState,
    downloadState: DjModelDownloadState
): DjCatalogSettingsUiState = djCatalogSettingsUiState(
    options = modelState.catalog?.models.orEmpty().map { DjCatalogOption(it.id, it.name) },
    selectedId = modelState.selectedId,
    // A model downloaded before the catalog existed has no ID but is still active.
    activeId = modelState.activeId ?: (downloadState as? DjModelDownloadState.Ready)?.file?.name,
    downloadState = downloadState
)

private fun djCatalogSettingsUiState(
    options: List<DjCatalogOption>,
    selectedId: String?,
    activeId: String?,
    downloadState: DjModelDownloadState
): DjCatalogSettingsUiState {
    val selected = options.firstOrNull { it.id == selectedId }
    val activeName = activeId?.let { id -> options.firstOrNull { it.id == id }?.displayName }
    return DjCatalogSettingsUiState(
        options = options,
        selectedName = selected?.displayName,
        activeLabel = when {
            activeName != null -> activeName
            activeId != null -> "Unavailable until catalog refresh"
            else -> "None"
        },
        canDownload = selected != null &&
            downloadState !is DjModelDownloadState.Downloading &&
            (downloadState == DjModelDownloadState.NotDownloaded ||
                downloadState is DjModelDownloadState.Failed ||
                selected.id != activeId)
    )
}

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
                            SettingsTab.PROVIDERS -> Text("Providers")
                            SettingsTab.AI_DJ -> Text("AI DJ")
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
                Text("Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

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

            SettingsTab.PROVIDERS -> ProvidersSettingsTab(
                viewModel = viewModel,
                state = state,
                spotifyStatus = spotifyStatus,
                jellyfinStatus = jellyfinStatus,
                plexStatus = plexStatus
            )
            SettingsTab.AI_DJ -> AiDjSettingsTab(viewModel, state)
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
