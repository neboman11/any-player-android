package com.anyplayer.android.core.di

import android.content.Context
import androidx.room.Room
import com.anyplayer.android.core.storage.AppDatabase
import com.anyplayer.android.core.storage.dao.AppCacheEntryDao
import com.anyplayer.android.core.storage.dao.ColumnPreferenceDao
import com.anyplayer.android.core.storage.dao.CustomPlaylistDao
import com.anyplayer.android.core.storage.dao.PlaylistTrackDao
import com.anyplayer.android.core.storage.dao.UnionPlaylistSourceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "any_player.db")
            .build()

    @Provides
    fun provideCustomPlaylistDao(database: AppDatabase): CustomPlaylistDao = database.customPlaylistDao()

    @Provides
    fun providePlaylistTrackDao(database: AppDatabase): PlaylistTrackDao = database.playlistTrackDao()

    @Provides
    fun provideUnionPlaylistSourceDao(database: AppDatabase): UnionPlaylistSourceDao = database.unionPlaylistSourceDao()

    @Provides
    fun provideColumnPreferenceDao(database: AppDatabase): ColumnPreferenceDao = database.columnPreferenceDao()

    @Provides
    fun provideAppCacheEntryDao(database: AppDatabase): AppCacheEntryDao = database.appCacheEntryDao()
}
