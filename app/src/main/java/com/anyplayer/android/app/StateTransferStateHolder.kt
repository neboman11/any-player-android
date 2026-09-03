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
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns MainViewModel's state-transfer (export/import) status and file operations.
 * [applyImportSummary] is also passed to SyncStateHolder so it can report its own
 * config-snapshot import results through this holder's status flow.
 */
internal class StateTransferStateHolder(
    private val viewModelScope: CoroutineScope,
    private val context: Context,
    private val stateTransferManager: StateTransferManager,
    private val configFileImporter: ConfigFileImporter,
    private val currentPlaybackStatus: () -> com.anyplayer.android.core.model.PlaybackStatus
) {
    val stateTransferStatus = MutableStateFlow("State transfer idle")

    private suspend fun <T> withOutputStream(uri: Uri, errorMessage: String, block: suspend (OutputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openOutputStream(uri) ?: error(errorMessage)
            stream.use { block(it) }
        }

    private suspend fun <T> withInputStream(uri: Uri, errorMessage: String, block: suspend (InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(uri) ?: error(errorMessage)
            stream.use { block(it) }
        }

    /** Runs [block] and reports its outcome via [onResult], without swallowing cancellation
     *  (letting a runCatching-style catch-all also catch CancellationException breaks
     *  structured concurrency when the ViewModel is cleared mid-flight). */
    private fun <T> launchTracked(block: suspend () -> T, onResult: (Result<T>) -> Unit) {
        viewModelScope.launch {
            val result = try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            onResult(result)
        }
    }

    fun exportStateToUri(uri: Uri, mode: ExportMode, includePlayback: Boolean, passphrase: String?) {
        launchTracked({
            withOutputStream(uri, "Could not open output stream for export") { stream ->
                stateTransferManager.exportToStream(
                    stream = stream,
                    options = ExportOptions(
                        mode = mode,
                        includePlaybackState = includePlayback,
                        passphrase = passphrase
                    ),
                    playbackStatus = currentPlaybackStatus()
                )
            }
            "Export complete"
        }) { result ->
            result.onSuccess { stateTransferStatus.value = it }
                .onFailure { stateTransferStatus.value = "Export failed: ${it.message}" }
        }
    }

    fun importStateFromUri(uri: Uri, policy: MergePolicy, passphrase: String?, dryRun: Boolean) {
        launchTracked({
            withInputStream(uri, "Could not open input stream for import") { stream ->
                stateTransferManager.importFromStream(
                    stream = stream,
                    options = ImportOptions(
                        mergePolicy = policy,
                        passphrase = passphrase,
                        dryRun = dryRun
                    )
                )
            }
        }) { result ->
            result.onSuccess { summary ->
                applyImportSummary(if (dryRun) "Dry run" else "Import", summary)
            }.onFailure {
                stateTransferStatus.value = "${if (dryRun) "Dry run" else "Import"} failed: ${it.message}"
            }
        }
    }

    fun importConfigFromUri(uri: Uri, policy: MergePolicy, dryRun: Boolean, onImported: () -> Unit) {
        launchTracked({
            withInputStream(uri, "Could not open input stream for config import") { stream ->
                configFileImporter.importFromStream(
                    stream = stream,
                    mergePolicy = policy,
                    dryRun = dryRun
                )
            }
        }) { result ->
            result.onSuccess { summary ->
                val prefix = if (dryRun) "Config dry run" else "Config import"
                applyImportSummary(prefix, summary)
                if (!dryRun) onImported()
            }.onFailure {
                stateTransferStatus.value = "Config import failed: ${it.message}"
            }
        }
    }

    fun applyImportSummary(prefix: String, summary: ImportSummary) {
        stateTransferStatus.value = formatSummary(prefix, summary)
    }

    private fun formatSummary(prefix: String, summary: ImportSummary): String {
        val warningSummary = if (summary.warnings.isEmpty()) "no warnings" else summary.warnings.joinToString(" | ")
        return "$prefix complete. playlists +${summary.playlistsAdded}/~${summary.playlistsUpdated}, tracks +${summary.tracksAdded}/~${summary.tracksUpdated}, union +${summary.unionLinksAdded}/~${summary.unionLinksUpdated}, connections +${summary.connectionsImported}/skip ${summary.connectionsSkipped}, $warningSummary"
    }
}
