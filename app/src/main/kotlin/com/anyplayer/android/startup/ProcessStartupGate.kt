package com.anyplayer.android.startup

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.playback.PlaybackEngine
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

class ProcessStartupGate @Inject constructor(private val engine: Provider<PlaybackEngine>) : StartupGate {
    override suspend fun awaitReady() {
        val foreground = callbackFlow {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            fun publish() { trySend(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
            val observer = LifecycleEventObserver { _, _ -> publish() }
            lifecycle.addObserver(observer)
            publish()
            awaitClose { lifecycle.removeObserver(observer) }
        }.flowOn(Dispatchers.Main.immediate)
        merge(foreground, engine.get().player.map { it.status == PlaybackStateType.PLAYING || it.playRequested }).first { it }
    }
}
