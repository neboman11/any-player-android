package com.anyplayer.android.di

import android.content.Context
import androidx.room.Room
import com.anyplayer.android.core.di.StorageModule
import com.anyplayer.android.core.storage.AppDatabase
import com.anyplayer.android.core.storage.dao.AppCacheEntryDao
import com.anyplayer.android.core.storage.dao.ColumnPreferenceDao
import com.anyplayer.android.core.storage.dao.CustomPlaylistDao
import com.anyplayer.android.core.storage.dao.PlaylistTrackDao
import com.anyplayer.android.core.storage.dao.ProviderPlaylistPreferenceDao
import com.anyplayer.android.core.storage.dao.UnionPlaylistSourceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Swaps the on-disk Room DB for an in-memory one so each test run starts clean. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [StorageModule::class])
object FakeStorageModule {
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
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

    @Provides
    fun provideProviderPlaylistPreferenceDao(database: AppDatabase): ProviderPlaylistPreferenceDao =
        database.providerPlaylistPreferenceDao()
}
