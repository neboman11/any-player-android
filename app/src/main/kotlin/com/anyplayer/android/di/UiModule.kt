package com.anyplayer.android.di

import com.anyplayer.android.ui.settings.SpotifyRedirectSource
import com.anyplayer.android.ui.shell.SpotifyRedirectHolder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class UiModule {
    @Binds
    abstract fun redirects(holder: SpotifyRedirectHolder): SpotifyRedirectSource
}
