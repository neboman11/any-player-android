package com.anyplayer.android.data.playlists

import com.anyplayer.android.data.transfer.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaylistTransferModule {
    @Binds abstract fun playlists(implementation: RoomPlaylistRepository): PlaylistRepository
    @Binds abstract fun sourceEditor(implementation: RoomPlaylistRepository): PlaylistSourceEditor
    @Binds abstract fun stateTransfer(implementation: LegacyStateTransfer): StateTransfer
    @Binds abstract fun configFiles(implementation: LegacyConfigFiles): ConfigFiles
}
