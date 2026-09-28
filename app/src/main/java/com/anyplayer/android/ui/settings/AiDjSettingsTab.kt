package com.anyplayer.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anyplayer.android.app.MainUiState
import com.anyplayer.android.app.MainViewModel
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.feature.djfiller.model.DjModelDownloadState
import java.text.DateFormat
import java.util.Locale
import kotlin.math.log10
import java.util.Date

@Composable
internal fun AiDjSettingsTab(viewModel: MainViewModel, state: MainUiState) {
    Text("AI DJ (Beta)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "Every few songs, an on-device AI DJ introduces what's coming up next. " +
            "Text generation and speech happen entirely on this device; only the " +
            "song, artist and album are sent to your sync server to fetch background passages.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val showDjEntriesInQueue by viewModel.showDjEntriesInQueue.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.aiDjEnabled,
            onClick = { viewModel.setAiDjEnabled(!state.aiDjEnabled) },
            label = { Text("Enable AI DJ") }
        )
        FilterChip(
            selected = showDjEntriesInQueue,
            onClick = { viewModel.setShowDjEntriesInQueue(!showDjEntriesInQueue) },
            label = { Text("Show DJ entries in queue") }
        )
    }
    if (state.aiDjEnabled) {
        val djVoiceModelDownloadState by viewModel.djVoiceModelDownloadState.collectAsState()
        val djVoiceCatalogState by viewModel.djVoiceCatalogState.collectAsState()
        LaunchedEffect(Unit) { viewModel.refreshDjVoiceCatalog() }
        val djScriptModelState by viewModel.djScriptModelState.collectAsState()
        LaunchedEffect(Unit) { viewModel.refreshDjModelCatalog() }
        WorkflowStep(number = 1, label = "AI DJ script model") {
            val modelUiState = djModelSettingsUiState(djScriptModelState, state.aiDjModelDownloadState)
            OutlinedButton(onClick = viewModel::refreshDjModelCatalog) { Text("Refresh models") }
            when {
                djScriptModelState.catalog == null -> Text(
                    djScriptModelState.catalogError
                        ?: "Model catalog unavailable. Configure and authenticate the sync server, then refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                modelUiState.options.isEmpty() -> Text(
                    "The sync server has no AI DJ models available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> modelUiState.options.forEach { model ->
                    FilterChip(
                        selected = model.id == djScriptModelState.selectedId,
                        onClick = { viewModel.selectDjModel(model.id) },
                        label = { Text(model.displayName) }
                    )
                }
            }
            Text(
                "Selected target: ${modelUiState.selectedName ?: "None"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Active model: ${modelUiState.activeLabel}", style = MaterialTheme.typography.bodySmall)
            when (val downloadState = state.aiDjModelDownloadState) {
                is DjModelDownloadState.Ready -> Column {
                    Text(
                        "Model ready (${downloadState.file.name})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (modelUiState.canDownload) {
                        Button(onClick = viewModel::downloadDjModel) { Text("Switch Model") }
                    }
                }
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
                    Button(onClick = viewModel::downloadDjModel, enabled = modelUiState.canDownload) {
                        Text("Retry Download")
                    }
                }
                DjModelDownloadState.NotDownloaded -> Button(
                    onClick = viewModel::downloadDjModel,
                    enabled = modelUiState.canDownload
                ) { Text("Download") }
            }
        }
        WorkflowStep(number = 2, label = "AI DJ speech voice") {
            val voiceUiState = djVoiceSettingsUiState(djVoiceCatalogState, djVoiceModelDownloadState)
            OutlinedButton(onClick = viewModel::refreshDjVoiceCatalog) { Text("Refresh voices") }
            when {
                djVoiceCatalogState.catalog == null -> Text(
                    "Voice catalog unavailable. Configure and authenticate the sync server, then refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                voiceUiState.options.isEmpty() -> Text(
                    "The sync server has no AI DJ voices available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> voiceUiState.options.forEach { voice ->
                    FilterChip(
                        selected = voice.id == djVoiceCatalogState.selectedId,
                        onClick = { viewModel.selectDjVoice(voice.id) },
                        label = { Text(voice.displayName) }
                    )
                }
            }
            Text(
                "Selected target: ${voiceUiState.selectedName ?: "None"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Active voice: ${voiceUiState.activeLabel}", style = MaterialTheme.typography.bodySmall)
            val voiceGain by viewModel.djVoiceGain.collectAsState()
            Text("Voice level: ${String.format(Locale.US, "%+.1f dB", 20 * log10(voiceGain))}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = voiceGain,
                valueRange = viewModel.djVoiceGainRange,
                onValueChange = viewModel::setDjVoiceGain
            )
            when (val downloadState = djVoiceModelDownloadState) {
                is DjModelDownloadState.Ready -> Column {
                    Text(
                        "Voice ready",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (voiceUiState.canDownload) {
                        Button(onClick = viewModel::downloadDjVoiceModel) { Text("Switch Voice") }
                    }
                }
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
                    Button(onClick = viewModel::downloadDjVoiceModel, enabled = voiceUiState.canDownload) {
                        Text("Retry Download")
                    }
                }
                DjModelDownloadState.NotDownloaded -> Button(
                    onClick = viewModel::downloadDjVoiceModel,
                    enabled = voiceUiState.canDownload
                ) { Text("Download") }
            }
        }
    }

    HorizontalDivider()
    Text("AI DJ Logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "Recent AI DJ logs from this app session. Entries may include track titles.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val logs by CompatLog.aiDjLogs.collectAsState()
    val clipboard = LocalClipboardManager.current
    val timeFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM)
    val lines = logs.asReversed().map { entry ->
        "${timeFormat.format(Date(entry.timestampMs))} ${entry.level}/${entry.tag}: ${entry.message}"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = CompatLog::clearAiDjLogs, enabled = logs.isNotEmpty()) {
            Text("Clear logs")
        }
        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) },
            enabled = logs.isNotEmpty()
        ) {
            Text("Copy logs")
        }
    }
    if (logs.isEmpty()) {
        Text("No AI DJ logs yet.", style = MaterialTheme.typography.bodySmall)
    } else {
        SelectionContainer {
            Text(
                lines.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            )
        }
    }
}
