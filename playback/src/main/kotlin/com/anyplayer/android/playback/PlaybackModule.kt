package com.anyplayer.android.playback

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PlaybackModule {
    @Binds
    abstract fun bindPlaybackEngine(engine: DefaultPlaybackEngine): PlaybackEngine
}
