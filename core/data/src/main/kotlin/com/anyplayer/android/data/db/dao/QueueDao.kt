package com.anyplayer.android.data.db.dao

import androidx.room.*
import com.anyplayer.android.data.db.entity.QueueEntryEntity
import com.anyplayer.android.data.db.entity.QueueMetaEntity

@Dao
abstract class QueueDao {
    @Query("SELECT * FROM queue_entries ORDER BY seq")
    abstract suspend fun entries(): List<QueueEntryEntity>
    @Query("SELECT * FROM queue_meta WHERE id = 0")
    abstract suspend fun meta(): QueueMetaEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertEntries(entries: List<QueueEntryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMeta(meta: QueueMetaEntity)
    @Query("DELETE FROM queue_entries")
    abstract suspend fun clearEntries()
    @Query("UPDATE queue_meta SET cursorUid = :uid WHERE id = 0 AND contentVersion = :version AND (:uid IS NULL OR EXISTS(SELECT 1 FROM queue_entries WHERE uid = :uid))")
    abstract suspend fun updateCursor(uid: Long?, version: Long)
    @Transaction
    open suspend fun replace(entries: List<QueueEntryEntity>, meta: QueueMetaEntity) {
        require(meta.id == QueueMetaEntity.SINGLETON_ID)
        clearEntries()
        insertEntries(entries)
        insertMeta(meta)
    }
    @Transaction
    open suspend fun snapshot(): QueueSnapshot = QueueSnapshot(entries(), meta())
}

data class QueueSnapshot(val entries: List<QueueEntryEntity>, val meta: QueueMetaEntity?)
