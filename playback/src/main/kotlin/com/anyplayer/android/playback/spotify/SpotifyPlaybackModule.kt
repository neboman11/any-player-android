package com.anyplayer.android.playback.spotify

import com.anyplayer.android.core.model.BackendKind
import com.anyplayer.android.playback.backend.BackendKindKey
import com.anyplayer.android.playback.backend.PlaybackBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SpotifyPlaybackModule {
    @Binds
    @IntoMap
    @BackendKindKey(BackendKind.SPOTIFY)
    abstract fun bindSpotifyBackend(impl: SpotifyBackend): PlaybackBackend

    @Binds
    abstract fun bindSpotifyPlayerApi(impl: OkHttpSpotifyPlayerApi): SpotifyPlayerApi

    @Binds
    abstract fun bindSpotifyAppLauncher(impl: AndroidSpotifyAppLauncher): SpotifyAppLauncher
}
