package com.anyplayer.android.feature.startup.di

import com.anyplayer.android.feature.providers.ProviderCatalogRepository
import com.anyplayer.android.feature.startup.StartupCatalogGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StartupModule {
    @Binds
    @Singleton
    abstract fun bindStartupCatalogGateway(
        providerCatalogRepository: ProviderCatalogRepository
    ): StartupCatalogGateway
}
