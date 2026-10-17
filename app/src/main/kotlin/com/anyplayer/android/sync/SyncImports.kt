package com.anyplayer.android.sync

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.data.auth.AuthRepository
import com.anyplayer.android.data.auth.ConnectionStore
import com.anyplayer.android.data.catalog.CatalogRepository
import com.anyplayer.android.data.transfer.ConfigFiles
import com.anyplayer.android.data.transfer.MergePolicy
import com.anyplayer.android.data.transfer.format
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/** Shared with Settings' state-transfer status line; sync status remains about transport. */
@Singleton
class SyncImportStatus @Inject constructor() {
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    internal fun publish(message: String) { mutableMessage.value = message }
}

/** Revalidates only imported provider URLs that actually changed, never re-runs startup. */
class SyncImports @Inject constructor(
    private val config: ConfigFiles,
    private val connections: ConnectionStore,
    private val auth: AuthRepository,
    private val catalog: CatalogRepository,
    private val status: SyncImportStatus,
) {
    suspend fun apply(file: JsonObject, policy: MergePolicy): Boolean {
        try {
            val before = connections.awaitLoaded()
            val summary = config.importJson(file, policy, dryRun = false)
            val warnings = mutableListOf<String>()
            val after = connections.awaitLoaded()
            var changed = false
            for (source in listOf(SourceType.JELLYFIN, SourceType.PLEX)) {
                val saved = after[source] ?: continue
                if (before[source]?.serverUrl == saved.serverUrl) continue
                changed = true
                try {
                    val token = saved.token?.takeIf(String::isNotBlank) ?: continue
                    if (source == SourceType.JELLYFIN) auth.connectJellyfin(saved.serverUrl.orEmpty(), token, saved.playlistPageSize)
                    else auth.connectPlex(saved.serverUrl.orEmpty(), token, saved.playlistPageSize)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    warnings += "Provider refresh failed: $source: ${e.message ?: "Unknown error"}"
                }
            }
            if (changed) {
                try { warnings += catalog.refreshPlaylists().map { "Provider refresh failed: ${it.source}: ${it.message}" } }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    warnings += "Provider refresh failed: ${e.message ?: "Unknown error"}"
                }
            }
            status.publish(summary.copy(warnings = summary.warnings + warnings).format("Sync import"))
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            status.publish("Sync import failed: ${e.message ?: "Unknown error"}")
            return false
        }
    }
}
