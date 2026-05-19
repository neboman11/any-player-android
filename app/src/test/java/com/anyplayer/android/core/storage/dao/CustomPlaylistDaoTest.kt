package com.anyplayer.android.core.storage.dao

import android.content.Context
import androidx.room.Room
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.storage.AppDatabase
import com.anyplayer.android.core.storage.entity.CustomPlaylistEntity
import com.anyplayer.android.core.storage.entity.UnionPlaylistSourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CustomPlaylistDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var customPlaylistDao: CustomPlaylistDao
    private lateinit var unionPlaylistSourceDao: UnionPlaylistSourceDao

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        customPlaylistDao = database.customPlaylistDao()
        unionPlaylistSourceDao = database.unionPlaylistSourceDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsert_updatesUnionPlaylistWithoutDeletingSources() = runBlocking {
        val playlistId = "union-1"
        customPlaylistDao.upsert(
            listOf(
                CustomPlaylistEntity(
                    id = playlistId,
                    name = "Union",
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                    trackCount = 1,
                    playlistType = PlaylistType.UNION,
                    isDistinct = false
                )
            )
        )
        unionPlaylistSourceDao.upsert(
            listOf(
                UnionPlaylistSourceEntity(
                    id = "source-1",
                    unionPlaylistId = playlistId,
                    sourceType = SourceType.SPOTIFY,
                    sourcePlaylistId = "playlist-123",
                    position = 0,
                    addedAt = "2026-01-01T00:00:00Z"
                )
            )
        )

        customPlaylistDao.upsert(
            listOf(
                CustomPlaylistEntity(
                    id = playlistId,
                    name = "Union Renamed",
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-02T00:00:00Z",
                    trackCount = 5,
                    playlistType = PlaylistType.UNION,
                    isDistinct = true
                )
            )
        )

        val savedPlaylist = customPlaylistDao.getById(playlistId)
        val savedSources = unionPlaylistSourceDao.getByUnionPlaylist(playlistId)

        assertEquals("Union Renamed", savedPlaylist?.name)
        assertEquals(5, savedPlaylist?.trackCount)
        assertEquals(true, savedPlaylist?.isDistinct)
        assertEquals(1, savedSources.size)
        assertEquals("playlist-123", savedSources.single().sourcePlaylistId)
    }
}
