package com.anyplayer.android.data.db.dao

import androidx.room.*
import com.anyplayer.android.data.db.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UnionCacheDao {
    @Query("SELECT * FROM cached_tracks WHERE ownerKey = :key ORDER BY position")
    fun observe(key: String): Flow<List<CachedTrackEntity>>
    @Query("SELECT * FROM cached_tracks WHERE ownerKey = :key ORDER BY position")
    suspend fun tracks(key: String): List<CachedTrackEntity>
    @Query("SELECT * FROM cached_track_owners WHERE ownerKey = :key")
    suspend fun owner(key: String): CachedTrackOwnerEntity?
    @Query("DELETE FROM cached_tracks WHERE ownerKey = :key")
    suspend fun clear(key: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tracks: List<CachedTrackEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOwner(owner: CachedTrackOwnerEntity)
    @Transaction
    suspend fun replace(owner: CachedTrackOwnerEntity, tracks: List<CachedTrackEntity>) {
        clear(owner.ownerKey)
        insert(tracks)
        putOwner(owner)
    }
}
