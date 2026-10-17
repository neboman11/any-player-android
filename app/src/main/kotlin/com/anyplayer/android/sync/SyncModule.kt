package com.anyplayer.android.sync

import com.anyplayer.android.data.sync.SyncEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds abstract fun syncEngine(implementation: DefaultSyncEngine): SyncEngine
    @Binds abstract fun syncNetwork(implementation: AndroidSyncNetwork): SyncNetwork
}
