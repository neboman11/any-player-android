package com.anyplayer.android.spotify

import com.anyplayer.android.BuildConfig
import com.anyplayer.android.data.db.LegacyCacheImporter
import com.anyplayer.android.di.AppModule
import com.anyplayer.android.startup.LegacyCacheImport
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Named

/** Set before Hilt injection; the non-empty default keeps other suites independent of CI secrets. */
object SpotifyTestClientId {
    const val DEFAULT = "integration-test-spotify-client"
    @Volatile var value: String = DEFAULT
}

/** Production app bindings unchanged except for the controllable Spotify client ID. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AppModule::class])
object SpotifyTestAppModule {
    @Provides @Named("spotifyClientId")
    fun spotifyClientId(): String = SpotifyTestClientId.value

    @Provides @Named("appVersionCode")
    fun appVersionCode(): Int = BuildConfig.VERSION_CODE

    @Provides @Named("appVersionName")
    fun appVersionName(): String = BuildConfig.VERSION_NAME

    @Provides
    fun legacyCacheImport(importer: LegacyCacheImporter): LegacyCacheImport = LegacyCacheImport { importer.runOnce() }
}
