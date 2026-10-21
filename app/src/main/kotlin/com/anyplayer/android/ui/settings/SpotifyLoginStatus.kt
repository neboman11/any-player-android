package com.anyplayer.android.ui.settings

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SpotifyLoginResult(val busy: Boolean = false, val feedback: String = "")

/** Keeps a claimed callback's outcome available when its Settings destination is popped. */
@Singleton
class SpotifyLoginStatus @Inject constructor() {
    private val mutableState = MutableStateFlow(SpotifyLoginResult())
    val state = mutableState.asStateFlow()

    internal fun publish(busy: Boolean, feedback: String) {
        mutableState.value = SpotifyLoginResult(busy, feedback)
    }
}
