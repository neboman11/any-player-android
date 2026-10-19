package com.anyplayer.android.playback

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.anyplayer.android.core.model.BackendKind
import com.anyplayer.android.playback.backend.BackendKindKey
import com.anyplayer.android.playback.backend.ExoBackend
import com.anyplayer.android.playback.backend.PlaybackBackend
import dagger.multibindings.IntoMap

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PlaybackModule {
    @Binds
    abstract fun bindPlaybackEngine(engine: DefaultPlaybackEngine): PlaybackEngine

    @Binds
    @IntoMap
    @BackendKindKey(BackendKind.EXO)
    abstract fun bindExoBackend(backend: ExoBackend): PlaybackBackend
}
