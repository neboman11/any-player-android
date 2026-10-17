package com.anyplayer.android.di

import com.anyplayer.android.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Named("spotifyClientId")
    fun spotifyClientId(): String = BuildConfig.SPOTIFY_CLIENT_ID

    @Provides @Named("appVersionCode")
    fun appVersionCode(): Int = BuildConfig.VERSION_CODE
}
