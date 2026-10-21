package com.anyplayer.android.ui.settings

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SpotifyLoginResult(
    val id: Long = 0,
    val busy: Boolean = false,
    val feedback: String = "",
    val acknowledged: Boolean = false,
)

/** Keeps a claimed callback's outcome available when its Settings destination is popped. */
@Singleton
class SpotifyLoginStatus @Inject constructor() {
    private val mutableState = MutableStateFlow(SpotifyLoginResult())
    val state = mutableState.asStateFlow()

    internal fun publish(busy: Boolean, feedback: String) {
        mutableState.update { SpotifyLoginResult(id = it.id + 1, busy = busy, feedback = feedback) }
    }

    internal fun acknowledge(id: Long) {
        mutableState.update { if (it.id == id) it.copy(acknowledged = true) else it }
    }
}
