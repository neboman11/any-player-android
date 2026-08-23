package com.anyplayer.android.di

import android.content.Context
import com.anyplayer.android.core.di.AudioCacheDirectory
import com.anyplayer.android.core.di.AudioCacheModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.io.File
import java.util.UUID
import javax.inject.Singleton

/**
 * Gives each test its own cache directory. [androidx.media3.datasource.cache.SimpleCache]
 * refuses to open a directory that's already locked by another instance in the same
 * process, which the shared production directory would trip across test methods since
 * Hilt recreates the singleton component (and thus a new SimpleCache) per test.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AudioCacheModule::class])
object FakeAudioCacheModule {
    @Provides
    @Singleton
    @AudioCacheDirectory
    fun provideAudioCacheDirectory(@ApplicationContext context: Context): File =
        File(context.cacheDir, "audio_cache_test_${UUID.randomUUID()}")
}
