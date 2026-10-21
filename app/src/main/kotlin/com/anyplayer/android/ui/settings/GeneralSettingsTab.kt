package com.anyplayer.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anyplayer.android.data.transfer.ExportMode
import com.anyplayer.android.data.transfer.MergePolicy
import kotlinx.coroutines.launch

@Composable
internal fun GeneralSettingsTab(viewModel: GeneralSettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Text("Sync", style = MaterialTheme.typography.titleMedium)
    SettingsField(state.serverTarget, viewModel::editTarget, "Sync Server Target", placeholder = "http://10.0.2.2:8080", onCommit = viewModel::commitFields)
    SettingsField(state.authToken, viewModel::editToken, "Sync Auth Token", secret = true, onCommit = viewModel::commitFields)
    val prefs = state.preferences
    listOf("app_state" to prefs.syncAppState, "playlists" to prefs.syncPlaylists,
        "provider_configuration" to prefs.syncProviderConfiguration, "settings" to prefs.syncSettings).forEach { (domain, enabled) ->
        FilterChip(selected = enabled, onClick = { viewModel.setDomain(domain, !enabled) }, label = { Text(domain) })
    }
    Button(onClick = viewModel::connect, enabled = !state.sync.inProgress) { Text("Connect") }
    OutlinedButton(onClick = viewModel::requestPull, enabled = !state.sync.inProgress) { Text("Force Pull from Server") }
    Text(state.sync.status)
    if (state.sync.playlistSyncPaused && state.sync.status != "Playlist sync paused: confirm in Settings") {
        Text("Playlist sync paused: confirm in Settings")
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    HorizontalDivider()
    Text("Playback", style = MaterialTheme.typography.titleMedium)
    FilterChip(state.normalization, { viewModel.setNormalization(!state.normalization) }, label = { Text("Normalize Audio Across Providers") })
    FilterChip(false, {}, enabled = false, label = { Text("Strict Normalization (Unavailable)") })
    HorizontalDivider()
    Text("Data", style = MaterialTheme.typography.titleMedium)
    DataWorkflows(viewModel, state.transferBusy)
    if (state.transferStatus != "State transfer idle") { HorizontalDivider(); Text(state.transferStatus) }
    if (state.confirmOverwrite) AlertDialog(onDismissRequest = viewModel::cancelPull,
        title = { Text("Confirm playlist overwrite") },
        text = { Text("Syncing playlists replaces local playlists with server state and may delete local-only playlists. Continue?") },
        confirmButton = { TextButton(onClick = viewModel::confirmPull) { Text("Continue") } },
        dismissButton = { Column {
            TextButton(onClick = { viewModel.resolveConflict(true) }) { Text("Keep this device's playlists") }
            TextButton(onClick = viewModel::cancelPull) { Text("Cancel") }
        } })
    if (state.sync.conflictPending) AlertDialog(onDismissRequest = viewModel::cancelConflict,
        title = { Text("Sync server already has data") },
        text = { Text("This server already has synced data for at least one enabled domain. Keep this device's data (overwrites the server) or take the server's data (overwrites this device)?") },
        confirmButton = { TextButton(onClick = { viewModel.resolveConflict(true) }) { Text("Keep This Device") } },
        dismissButton = { Column {
            TextButton(onClick = { viewModel.resolveConflict(false) }) { Text("Use Server Data") }
            TextButton(onClick = viewModel::cancelConflict) { Text("Cancel") }
        } })
}

private enum class DataWorkflow { NONE, EXPORT, IMPORT, CONFIG }

@Composable
private fun DataWorkflows(viewModel: GeneralSettingsViewModel, busy: Boolean) {
    var workflow by rememberSaveable { mutableStateOf(DataWorkflow.NONE.name) }
    var modeName by rememberSaveable { mutableStateOf(ExportMode.PORTABLE.name) }
    var policyName by rememberSaveable { mutableStateOf(MergePolicy.MERGE_KEEP_LOCAL.name) }
    // Secrets deliberately never enter saved-instance state.
    var passphrase by remember { mutableStateOf("") }
    var stateUri by rememberSaveable { mutableStateOf<String?>(null) }
    var stateName by rememberSaveable { mutableStateOf<String?>(null) }
    var configUri by rememberSaveable { mutableStateOf<String?>(null) }
    var configName by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val mode = ExportMode.valueOf(modeName)
    val policy = MergePolicy.valueOf(policyName)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.export(it.toString(), mode, passphrase) }
    }
    val stateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val selected = it.toString(); stateUri = selected; stateName = null
            scope.launch { val name = viewModel.documentName(selected); if (stateUri == selected) stateName = name }
        }
    }
    val configLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val selected = it.toString(); configUri = selected; configName = null
            scope.launch { val name = viewModel.documentName(selected); if (configUri == selected) configName = name }
        }
    }
    if (workflow == DataWorkflow.NONE.name) {
        Text("Choose what you'd like to do:")
        WorkflowCard("Export state", "Save your playlists, tracks and settings to a file you can restore later or transfer to another device.", !busy) { workflow = DataWorkflow.EXPORT.name }
        WorkflowCard("Import state", "Restore from a previously exported Any Player state file (.json). Optionally preview with a dry run first.", !busy) { workflow = DataWorkflow.IMPORT.name }
        WorkflowCard("Import config file", "Apply a config file from the Any Player companion app. This imports playlists and server URLs without touching auth tokens.", !busy) { workflow = DataWorkflow.CONFIG.name }
        return
    }
    TextButton(onClick = { workflow = DataWorkflow.NONE.name; passphrase = "" }, enabled = !busy) { Text("← Back") }
    if (workflow == DataWorkflow.EXPORT.name) {
        Text("Export state", style = MaterialTheme.typography.titleSmall)
        Text("1. Choose export mode")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportMode.entries.forEach { choice -> FilterChip(mode == choice, { modeName = choice.name }, enabled = !busy, label = { Text(choice.name.lowercase()) }) }
        }
        Text(if (mode == ExportMode.PORTABLE) "Portable: playlists and server URLs only, no auth tokens. Safe to share." else "Private: includes auth tokens. Encrypt with a passphrase.")
        if (mode == ExportMode.PRIVATE) SettingsField(passphrase, { passphrase = it }, "Passphrase", secret = true)
        Button(onClick = { exportLauncher.launch("any-player-state.json") }, enabled = !busy && (mode == ExportMode.PORTABLE || passphrase.isNotBlank())) { Text("Choose location & Export") }
    } else {
        val config = workflow == DataWorkflow.CONFIG.name
        val selectedUri = if (config) configUri else stateUri
        val selectedName = if (config) configName else stateName
        Text(if (config) "Import config file" else "Import state", style = MaterialTheme.typography.titleSmall)
        if (config) Text("Config files come from the Any Player companion app. They carry playlists and server URLs — auth tokens are never included, so your existing credentials are preserved.")
        Text("1. Choose merge policy")
        MergePolicy.entries.forEach { choice -> FilterChip(policy == choice, { policyName = choice.name }, enabled = !busy, label = { Text(choice.name.lowercase().replace('_', ' ')) }) }
        Text(when (policy) {
            MergePolicy.REPLACE_ALL -> "Clears all existing playlists and replaces them with the import."
            MergePolicy.MERGE_KEEP_LOCAL -> "Adds new items; keeps your local version when there's a conflict."
            MergePolicy.MERGE_PREFER_IMPORT -> "Adds and updates; imported version wins on conflict."
        })
        if (!config) SettingsField(passphrase, { passphrase = it }, "Passphrase (optional)", secret = true)
        OutlinedButton(onClick = {
            val types = arrayOf("application/json", "application/octet-stream", "*/*")
            if (config) configLauncher.launch(types) else stateLauncher.launch(types)
        }, enabled = !busy) { Text(if (selectedUri != null) "✓ ${selectedName ?: "selected file"}" else "Choose file…") }
        Text("Tip: run a dry run first to preview what will change without modifying any data.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { selectedUri?.let { viewModel.import(it, policy, passphrase, true, config) } }, enabled = selectedUri != null && !busy) { Text("Dry Run") }
            Button(onClick = { selectedUri?.let { viewModel.import(it, policy, passphrase, false, config) } }, enabled = selectedUri != null && !busy) { Text("Import") }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun WorkflowCard(title: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
