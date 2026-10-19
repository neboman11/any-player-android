package com.anyplayer.android.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anyplayer.android.dj.*

@Composable
internal fun AiDjSettingsTab(viewModel: AiDjSettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { viewModel.enter(); onDispose { viewModel.leave() } }
    Text("AI DJ (Beta)", style = MaterialTheme.typography.titleMedium)
    Text(if (state.remote) "Every few songs, an AI DJ introduces what's coming up next. Your sync server writes and voices each introduction from background passages about the song."
        else "Every few songs, an AI DJ introduces what's coming up next. Text generation and speech happen entirely on this device; only the song, artist and album are sent to your sync server to fetch background passages.")
    FilterChip(state.enabled, { viewModel.setEnabled(!state.enabled) }, label = { Text("Enable AI DJ") })
    FilterChip(state.showEntries, { viewModel.setShowEntries(!state.showEntries) }, label = { Text("Show DJ entries in queue") })
    if (state.enabled) {
        Text("Generate DJ breaks on")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(state.remote, { viewModel.setRemote(true) }, label = { Text("Sync server") })
            FilterChip(!state.remote, { viewModel.setRemote(false) }, label = { Text("This device") })
        }
        if (!state.remote) ArtifactPicker("AI DJ script model", "model", state.models, state.modelDownload,
            viewModel::refreshCatalogs, viewModel::selectModel, viewModel::downloadModel)
        ArtifactPicker("AI DJ speech voice", "voice", state.voices, state.voiceDownload,
            viewModel::refreshCatalogs, viewModel::selectVoice, viewModel::downloadVoice, local = !state.remote)
        Text(voiceLevelLabel(state.voiceLevel))
        Slider(value = state.voiceLevel, onValueChange = viewModel::editVoiceLevel,
            onValueChangeFinished = viewModel::commitVoiceLevel, valueRange = viewModel.voiceLevelRange)
        Text("Downloaded on-device models", style = MaterialTheme.typography.titleSmall)
        if (state.downloadedModels.isEmpty() && state.downloadedVoices.isEmpty()) Text("Nothing downloaded.")
        state.downloadedModels.forEach { item -> DownloadedFileRow("Script model", item,
            state.modelDownload !is ArtifactDownloadState.Downloading) { viewModel.deleteModel(item) } }
        state.downloadedVoices.forEach { item -> DownloadedFileRow("Voice", item,
            state.voiceDownload !is ArtifactDownloadState.Downloading) { viewModel.deleteVoice(item) } }
    }
    HorizontalDivider()
    Text("AI DJ Logs", style = MaterialTheme.typography.titleMedium)
    Text("Recent AI DJ logs from this app session. Entries may include track titles.")
    FilterChip(state.debug, { viewModel.setDebug(!state.debug) }, label = { Text("Enable debug logging") })
    val clipboard = LocalClipboardManager.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = viewModel::clearLogs, enabled = state.logs.isNotEmpty()) { Text("Clear logs") }
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(viewModel.copyLogs())) }, enabled = state.logs.isNotEmpty()) { Text("Copy logs") }
    }
    if (state.logs.isEmpty()) Text("No AI DJ logs yet.")
    else SelectionContainer {
        LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
            itemsIndexed(state.logs) { _, entry ->
                Text("${entry.timestamp} ${entry.tag}: ${entry.message}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun <T : ArtifactDescriptor> ArtifactPicker(title: String, kind: String, catalog: ArtifactCatalogState<T>,
    download: ArtifactDownloadState, refresh: () -> Unit, select: (String) -> Unit,
    startDownload: () -> Unit, local: Boolean = true) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    OutlinedButton(onClick = refresh, enabled = !catalog.loading) { Text(if (kind == "model") "Refresh models" else "Refresh voices") }
    if (catalog.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    catalog.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (catalog.items.isEmpty() && !catalog.loading) Text(if (catalog.error != null)
        "${kind.replaceFirstChar(Char::uppercaseChar)} catalog unavailable. Configure and authenticate the sync server, then refresh."
        else "The sync server has no AI DJ ${kind}s available.")
    catalog.items.forEach { option -> FilterChip(catalog.selectedId == option.id, { select(option.id) }, label = { Text(option.name) }) }
    Text("Selected target: ${catalog.items.firstOrNull { it.id == catalog.selectedId }?.name ?: "None"}")
    if (!local) return
    Text("Active $kind: ${activeArtifactLabel(catalog, download)}")
    val available = canDownload(catalog, download)
    when (download) {
        is ArtifactDownloadState.Ready -> {
            Text("${if (kind == "model") "Model" else "Voice"} ready (${download.file.name})")
            if (available) Button(onClick = startDownload) { Text(if (kind == "model") "Switch Model" else "Switch Voice") }
        }
        is ArtifactDownloadState.Downloading -> {
            Text("Downloading... ${(download.progress * 100).toInt()}%")
            LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth())
        }
        is ArtifactDownloadState.Failed -> {
            Text(download.reason, color = MaterialTheme.colorScheme.error)
            Button(onClick = startDownload, enabled = available) { Text("Retry Download") }
        }
        ArtifactDownloadState.NotDownloaded -> Button(onClick = startDownload, enabled = available) { Text("Download") }
    }
}

@Composable
private fun DownloadedFileRow(label: String, item: DownloadedArtifact, enabled: Boolean, delete: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("$label: ${item.id ?: "Legacy"} (${item.version})")
            Text(Formatter.formatShortFileSize(context, item.sizeBytes) + if (item.active) " · in use" else "")
        }
        OutlinedButton(onClick = delete, enabled = enabled) { Text("Delete") }
    }
}
