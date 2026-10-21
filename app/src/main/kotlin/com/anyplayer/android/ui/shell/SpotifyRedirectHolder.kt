package com.anyplayer.android.ui.shell

import com.anyplayer.android.ui.settings.SpotifyRedirectSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * Holds the latest browser callback until it is consumed: once by the shell, which opens Settings,
 * and once by a Settings ViewModel, which handles it. Neither sees a consumed callback again.
 */
@Singleton
class SpotifyRedirectHolder @Inject constructor() : SpotifyRedirectSource {
    private val unhandled = MutableStateFlow<String?>(null)
    private val settingsNotOpened = MutableStateFlow<String?>(null)
    override val redirects: Flow<String> = unhandled.filterNotNull()
    /** Callbacks the shell has not yet opened Settings for. */
    val settingsRequests: Flow<String> = settingsNotOpened.filterNotNull()
    private var lastPublished: String? = null

    /** Ignores a repeat of the last callback, e.g. the launch Intent seen again after recreation. */
    fun publish(uri: String) {
        if (lastPublished == uri) return
        lastPublished = uri
        settingsNotOpened.value = uri
        unhandled.value = uri
    }

    override fun consume(uri: String): Boolean = unhandled.compareAndSet(uri, null)

    fun settingsOpened(uri: String) {
        settingsNotOpened.compareAndSet(uri, null)
    }
}
