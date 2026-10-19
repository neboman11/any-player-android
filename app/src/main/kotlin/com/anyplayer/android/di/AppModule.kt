package com.anyplayer.android.di

import com.anyplayer.android.BuildConfig
import com.anyplayer.android.data.db.LegacyCacheImporter
import com.anyplayer.android.startup.LegacyCacheImport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/** App-provided values and cross-module adapters that only :app can wire. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Named("spotifyClientId")
    fun spotifyClientId(): String = BuildConfig.SPOTIFY_CLIENT_ID

    @Provides @Named("appVersionCode")
    fun appVersionCode(): Int = BuildConfig.VERSION_CODE

    @Provides @Named("appVersionName")
    fun appVersionName(): String = BuildConfig.VERSION_NAME

    /** Present binding for StartupModule's @BindsOptionalOf: legacy blob caches are imported once at startup. */
    @Provides
    fun legacyCacheImport(importer: LegacyCacheImporter): LegacyCacheImport = LegacyCacheImport { importer.runOnce() }
}
