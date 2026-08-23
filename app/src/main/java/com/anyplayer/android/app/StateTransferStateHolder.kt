package com.anyplayer.android.app

import android.content.Context
import android.net.Uri
import com.anyplayer.android.feature.state.transfer.ConfigFileImporter
import com.anyplayer.android.feature.state.transfer.ExportMode
import com.anyplayer.android.feature.state.transfer.ExportOptions
import com.anyplayer.android.feature.state.transfer.ImportOptions
import com.anyplayer.android.feature.state.transfer.ImportSummary
import com.anyplayer.android.feature.state.transfer.MergePolicy
import com.anyplayer.android.feature.state.transfer.StateTransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns MainViewModel's state-transfer (export/import) status and file operations.
 * [stateTransferStatus] and [formatSummary] are also written/used by MainViewModel's
 * sync code path (config-snapshot import), so both stay internal rather than private.
 */
internal class StateTransferStateHolder(
    private val viewModelScope: CoroutineScope,
    private val context: Context,
    private val stateTransferManager: StateTransferManager,
    private val configFileImporter: ConfigFileImporter,
    private val currentPlaybackStatus: () -> com.anyplayer.android.core.model.PlaybackStatus
) {
    val stateTransferStatus = MutableStateFlow("State transfer idle")

    fun exportStateToUri(uri: Uri, mode: ExportMode, includePlayback: Boolean, passphrase: String?) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)
                        ?.use { stream ->
                            stateTransferManager.exportToStream(
                                stream = stream,
                                options = ExportOptions(
                                    mode = mode,
                                    includePlaybackState = includePlayback,
                                    passphrase = passphrase
                                ),
                                playbackStatus = currentPlaybackStatus()
                            )
                        } ?: error("Could not open output stream for export")
                }
                "Export complete"
            }.onSuccess { stateTransferStatus.value = it }
                .onFailure { stateTransferStatus.value = "Export failed: ${it.message}" }
        }
    }

    fun importStateFromUri(uri: Uri, policy: MergePolicy, passphrase: String?, dryRun: Boolean) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.use { stream ->
                            stateTransferManager.importFromStream(
                                stream = stream,
                                options = ImportOptions(
                                    mergePolicy = policy,
                                    passphrase = passphrase,
                                    dryRun = dryRun
                                )
                            )
                        } ?: error("Could not open input stream for import")
                }
            }.onSuccess { summary ->
                stateTransferStatus.value = formatSummary(if (dryRun) "Dry run" else "Import", summary)
            }.onFailure {
                stateTransferStatus.value = "${if (dryRun) "Dry run" else "Import"} failed: ${it.message}"
            }
        }
    }

    fun importConfigFromUri(uri: Uri, policy: MergePolicy, dryRun: Boolean, onImported: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.use { stream ->
                            configFileImporter.importFromStream(
                                stream = stream,
                                mergePolicy = policy,
                                dryRun = dryRun
                            )
                        } ?: error("Could not open input stream for config import")
                }
            }.onSuccess { summary ->
                val prefix = if (dryRun) "Config dry run" else "Config import"
                stateTransferStatus.value = formatSummary(prefix, summary)
                if (!dryRun) onImported()
            }.onFailure {
                stateTransferStatus.value = "Config import failed: ${it.message}"
            }
        }
    }

    fun formatSummary(prefix: String, summary: ImportSummary): String {
        val warningSummary = if (summary.warnings.isEmpty()) "no warnings" else summary.warnings.joinToString(" | ")
        return "$prefix complete. playlists +${summary.playlistsAdded}/~${summary.playlistsUpdated}, tracks +${summary.tracksAdded}/~${summary.tracksUpdated}, union +${summary.unionLinksAdded}/~${summary.unionLinksUpdated}, connections +${summary.connectionsImported}/skip ${summary.connectionsSkipped}, $warningSummary"
    }
}
