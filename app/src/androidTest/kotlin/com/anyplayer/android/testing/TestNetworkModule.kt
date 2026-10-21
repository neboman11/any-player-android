package com.anyplayer.android.testing

import com.anyplayer.android.data.auth.*
import com.anyplayer.android.data.catalog.CatalogRepository
import com.anyplayer.android.data.catalog.RoomCatalogRepository
import com.anyplayer.android.data.providers.ProviderDataModule
import com.anyplayer.android.data.settings.*
import com.anyplayer.android.data.store.SecureConnectionStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** Where Spotify traffic goes in tests; null leaves requests to the real hosts untouched. */
object SpotifyHosts {
    @Volatile var server: HttpUrl? = null
    private val prefixes = mapOf("api.spotify.com" to "spotify-api", "accounts.spotify.com" to "spotify-accounts")

    internal fun rewrite(url: HttpUrl): HttpUrl {
        val target = server ?: return url
        val prefix = prefixes[url.host] ?: return url
        return url.newBuilder().scheme(target.scheme).host(target.host).port(target.port)
            .encodedPath("/$prefix${url.encodedPath}").build()
    }
}

private class SpotifyRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val rewritten = SpotifyHosts.rewrite(request.url)
        return chain.proceed(if (rewritten === request.url) request else request.newBuilder().url(rewritten).build())
    }
}

/** Production [ProviderDataModule] bindings, with the shared client able to redirect Spotify hosts to a [TestServer]. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ProviderDataModule::class])
abstract class TestNetworkModule {
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
            .addInterceptor(SpotifyRedirectInterceptor())
            .build()
    }
}
