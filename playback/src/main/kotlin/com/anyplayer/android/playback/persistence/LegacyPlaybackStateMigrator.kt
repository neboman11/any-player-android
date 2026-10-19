package com.anyplayer.android.playback.persistence

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.db.AppDatabase
import com.anyplayer.android.data.settings.*
import com.anyplayer.android.data.store.SecurePrefs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class LegacyPlaybackState(
    val queue: List<Track> = emptyList(),
    val orderedQueue: List<Track>? = null,
    val currentQueueIndex: Int? = null,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val volume: Int = 100,
    val audioNormalizationEnabled: Boolean = false,
    val audioNormalizationStrictMode: Boolean = false,
    val aiDjEnabled: Boolean = false,
    val showDjEntriesInQueue: Boolean = false,
) {
    fun toQueue(): QueueState {
        val entries = queue.mapIndexed { i, track -> QueueEntry(i + 1L, track) }
        val remaining = queue.indices.toMutableSet()
        val order = if (shuffle) buildList {
            for (track in orderedQueue.orEmpty()) {
                val index = remaining.firstOrNull { queue[it] == track }
                    ?: remaining.firstOrNull { queue[it].source == track.source && trackIdsMatch(queue[it].id, track.id) }
                if (index != null) { add(index); remaining.remove(index) }
            }
            addAll(remaining)
        } else queue.indices.toList()
        val insertionIndex = if (queue.isEmpty()) -1 else (currentQueueIndex ?: 0).coerceIn(queue.indices)
        return QueueState(entries, order, order.indexOf(insertionIndex), shuffle, repeatMode,
            contentVersion = 1, nextUid = entries.size + 1L)
    }
    fun toSettings() = PlayerSettings(shuffle, repeatMode, volume.coerceIn(0, 100), positionMs.coerceAtLeast(0),
        audioNormalizationEnabled, audioNormalizationStrictMode, aiDjEnabled, showDjEntriesInQueue)
}

@Singleton
class LegacyPlaybackStateMigrator @Inject constructor(
    private val secure: SecurePrefs,
    private val database: AppDatabase,
    private val settings: PlayerSettingsStore,
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val json = AppJson
    suspend fun migrate() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = secure.open(SecurePrefs.PLAYBACK_STATE)
            val encoded = prefs.getString("status", null) ?: return@withLock
            val legacy = json.decodeFromString<LegacyPlaybackState>(encoded)
            val flags = legacy.toSettings()
            settings.awaitLoaded()
            settings.update { flags }
            val dao = database.queueDao()
            // A previous migration may have committed Room before process death. Never overwrite it.
            if (dao.meta() == null) {
                val queue = legacy.toQueue()
                dao.replace(queue.toRows(json), queue.toMeta())
            }
            // PlayerSettingsStore writes asynchronously; make the one-shot migration durable
            // before retiring the only legacy copy (including a restart after the Room commit).
            check(context.getSharedPreferences("player_settings", Context.MODE_PRIVATE).edit()
                .putBoolean("shuffle", flags.shuffle).putString("repeat", flags.repeat.name)
                .putInt("volume", flags.volume).putLong("positionMs", flags.positionMs)
                .putBoolean("normalizationEnabled", flags.normalizationEnabled)
                .putBoolean("normalizationStrict", flags.normalizationStrict)
                .putBoolean("aiDjEnabled", flags.aiDjEnabled)
                .putBoolean("showDjEntriesInQueue", flags.showDjEntriesInQueue).commit())
            check(prefs.edit().remove("status").commit()) { "Could not retire legacy playback state" }
        }
    }
}
