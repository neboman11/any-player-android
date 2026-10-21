package com.anyplayer.android.data.transfer

import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.JsonElement

enum class ExportMode { PORTABLE, PRIVATE }

enum class MergePolicy { REPLACE_ALL, MERGE_KEEP_LOCAL, MERGE_PREFER_IMPORT }

data class ImportSummary(
    val playlistsAdded: Int = 0,
    val playlistsUpdated: Int = 0,
    val tracksAdded: Int = 0,
    val tracksUpdated: Int = 0,
    val unionSourcesAdded: Int = 0,
    val unionSourcesUpdated: Int = 0,
    val connectionsImported: Int = 0,
    val connectionsSkipped: Int = 0,
    val warnings: List<String> = emptyList(),
    /** Exported player flags the caller must apply to the live engine; null for dry runs, MERGE_KEEP_LOCAL or files without playback state. */
    val playbackSettings: ImportedPlaybackSettings? = null,
)

/** The player flags an `any-player-state` file carries in `playbackState`. */
data class ImportedPlaybackSettings(val volume: Int, val shuffle: Boolean, val repeat: RepeatMode)

/** Legacy summary line: "$prefix complete. playlists +A/~U, tracks +A/~U, union +A/~U, connections +I/skip S, <warnings>". */
fun ImportSummary.format(prefix: String): String =
    "$prefix complete. playlists +$playlistsAdded/~$playlistsUpdated, tracks +$tracksAdded/~$tracksUpdated, " +
        "union +$unionSourcesAdded/~$unionSourcesUpdated, connections +$connectionsImported/skip $connectionsSkipped, " +
        (warnings.takeIf { it.isNotEmpty() }?.joinToString(" | ") ?: "no warnings")

/** Device-to-device state file (legacy `any-player-state` envelope, byte-compatible). */
interface StateTransfer {
    suspend fun export(output: OutputStream, mode: ExportMode, passphrase: String?, playback: PlaybackStatus?)
    suspend fun import(input: InputStream, policy: MergePolicy, passphrase: String?, dryRun: Boolean): ImportSummary
}

/** Companion/desktop config-file format (`export_version` 1); also the sync playlists/provider-config wire shape. */
interface ConfigFiles {
    suspend fun import(input: InputStream, policy: MergePolicy, dryRun: Boolean): ImportSummary

    /** Sync pull: `{export_version, provider_configs, custom_playlists}` as JSON. */
    suspend fun importJson(config: JsonElement, policy: MergePolicy, dryRun: Boolean): ImportSummary

    /** Sync push payloads (legacy encoding with explicit nulls). */
    suspend fun exportPlaylistsJson(): JsonElement
    suspend fun exportProviderConfigJson(): JsonElement
}
