package com.anyplayer.android.di

import com.anyplayer.android.fakes.FakeProviderAuthRepository
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import com.anyplayer.android.feature.auth.di.AuthModule
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AuthModule::class])
abstract class FakeAuthModule {
    @Binds
    @Singleton
    abstract fun bindProviderAuthRepository(impl: FakeProviderAuthRepository): ProviderAuthRepository
}
