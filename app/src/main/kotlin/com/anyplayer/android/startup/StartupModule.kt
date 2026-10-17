package com.anyplayer.android.startup

import com.anyplayer.android.data.sync.AppStartup
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class StartupModule {
    @Binds abstract fun appStartup(implementation: DefaultAppStartup): AppStartup
    @Binds abstract fun startupGate(implementation: ProcessStartupGate): StartupGate
    @BindsOptionalOf abstract fun legacyCacheImport(): LegacyCacheImport
}
