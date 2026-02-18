package com.anyplayer.android.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SpotifyAuthRedirectBus {
    private val mutableRedirectUri = MutableStateFlow<String?>(null)
    val redirectUri: StateFlow<String?> = mutableRedirectUri

    fun publish(uri: String) {
        mutableRedirectUri.value = uri
    }

    fun clear() {
        mutableRedirectUri.value = null
    }
}
