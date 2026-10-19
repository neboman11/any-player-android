package com.anyplayer.android.playback.persistence

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.db.AppDatabase
import com.anyplayer.android.data.db.entity.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface PlaybackPersistence {
    suspend fun restore(): QueueState
    suspend fun writeContent(queue: QueueState)
    suspend fun writeCursor(queue: QueueState)
}

internal fun QueueState.toRows(json: Json) = entries.mapIndexed { seq, entry ->
    QueueEntryEntity(entry.uid, seq, json.encodeToString(entry.track))
}
internal fun QueueState.toMeta() = QueueMetaEntity(orderUids = order.joinToString(",") { entries[it].uid.toString() },
    cursorUid = current?.uid, contentVersion = contentVersion)

@Singleton
class RoomPlaybackPersistence @Inject constructor(
    private val database: AppDatabase,
    private val migrator: LegacyPlaybackStateMigrator,
) : PlaybackPersistence {
    private val json = AppJson
    private val mutex = Mutex()
    override suspend fun restore(): QueueState = withContext(Dispatchers.IO) {
        migrator.migrate()
        val snapshot = database.queueDao().snapshot()
        val meta = snapshot.meta ?: return@withContext QueueState()
        val entries = snapshot.entries.map { QueueEntry(it.uid, json.decodeFromString<Track>(it.trackJson)) }
        val indices = entries.mapIndexed { i, e -> e.uid to i }.toMap()
        val order = meta.orderUids.split(',').mapNotNull { indices[it.toLongOrNull()] }.distinct()
        require(order.size == entries.size) { "Persisted queue order is incomplete" }
        QueueState(entries, order, order.indexOfFirst { entries[it].uid == meta.cursorUid },
            contentVersion = meta.contentVersion, nextUid = (entries.maxOfOrNull { it.uid } ?: 0) + 1)
    }
    override suspend fun writeContent(queue: QueueState) = withContext(Dispatchers.IO) {
        mutex.withLock { database.queueDao().replace(queue.toRows(json), queue.toMeta()) }
    }
    override suspend fun writeCursor(queue: QueueState) = withContext(Dispatchers.IO) {
        mutex.withLock { database.queueDao().updateCursor(queue.current?.uid, queue.contentVersion) }
    }
}
