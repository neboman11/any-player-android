package com.anyplayer.android.data.providers

import com.anyplayer.android.data.BuildConfig
import com.anyplayer.android.data.auth.*
import com.anyplayer.android.data.catalog.CatalogRepository
import com.anyplayer.android.data.catalog.RoomCatalogRepository
import com.anyplayer.android.data.settings.*
import com.anyplayer.android.data.store.SecureConnectionStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderDataModule {
    @Binds abstract fun connections(impl: SecureConnectionStore): ConnectionStore
    @Binds abstract fun sync(impl: SecureSyncSettingsStore): SyncSettingsStore
    @Binds abstract fun dj(impl: SecureDjSettingsStore): DjSettingsStore
    @Binds abstract fun player(impl: PlainPlayerSettingsStore): PlayerSettingsStore
    @Binds abstract fun sessions(impl: SecureSpotifyAuthSessionStore): SpotifyAuthSessionStore
    @Binds abstract fun tokens(impl: DefaultSpotifyTokenProvider): SpotifyTokenProvider
    @Binds abstract fun auth(impl: DefaultAuthRepository): AuthRepository
    @Binds abstract fun catalog(impl: RoomCatalogRepository): CatalogRepository

    companion object {
        @Provides @Singleton
        fun http(): OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS)
            .apply { if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)) }
            .build()
    }
}
