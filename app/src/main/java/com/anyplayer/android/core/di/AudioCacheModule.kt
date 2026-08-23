package com.anyplayer.android.core.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AudioCacheDirectory

@Module
@InstallIn(SingletonComponent::class)
object AudioCacheModule {
    @Provides
    @Singleton
    @AudioCacheDirectory
    fun provideAudioCacheDirectory(@ApplicationContext context: Context): File =
        File(context.cacheDir, "audio_cache")
}
