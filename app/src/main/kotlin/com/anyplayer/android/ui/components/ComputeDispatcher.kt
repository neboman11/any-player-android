package com.anyplayer.android.ui.components

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Dispatcher for UI model building over large lists (sorting, dedupe, queue rows). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ComputeDispatcher

@Module
@InstallIn(SingletonComponent::class)
object UiDispatchersModule {
    @Provides
    @ComputeDispatcher
    fun provideComputeDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
