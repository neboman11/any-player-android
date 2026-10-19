package com.anyplayer.android.ui.shell

import com.anyplayer.android.ui.settings.SpotifyRedirectSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Replays a browser callback until the Settings destination has had a chance to handle it. */
@Singleton
class SpotifyRedirectHolder @Inject constructor() : SpotifyRedirectSource {
    private val mutableRedirects = MutableSharedFlow<String>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val redirects = mutableRedirects.asSharedFlow()
    private var lastPublished: String? = null

    fun publish(uri: String) {
        if (lastPublished == uri) return
        lastPublished = uri
        mutableRedirects.tryEmit(uri)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearHandled() {
        mutableRedirects.resetReplayCache()
    }
}
