package com.anyplayer.android.ui.shell

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.data.sync.AppStartup
import com.anyplayer.android.playback.PlaybackEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

@Immutable
data class MiniPlayerUi(
    val visible: Boolean = false,
    val title: String = "—",
    val artist: String = "—",
    val imageUrl: String? = null,
    val playing: Boolean = false,
    val playEnabled: Boolean = true,
)

@Immutable
data class StartupBannerUi(
    val busy: Boolean = false,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
) {
    val visible: Boolean get() = busy || warnings.isNotEmpty()
}

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val engine: PlaybackEngine,
    private val startup: AppStartup,
    private val redirects: SpotifyRedirectHolder,
) : ViewModel() {
    private val currentTrack = engine.queue.map { it.current?.track }.distinctUntilChanged()
    val miniPlayer = combine(currentTrack, engine.player) { track, player ->
        MiniPlayerUi(
            visible = track != null || player.activeBreak != null,
            title = if (player.activeBreak != null) "AnyPlayer DJ" else track?.title ?: "—",
            artist = if (player.activeBreak != null) "—" else track?.artist ?: "—",
            imageUrl = if (player.activeBreak != null) null else track?.imageUrl,
            playing = player.status == PlaybackStateType.PLAYING,
            playEnabled = player.disabledReason == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MiniPlayerUi())
    val startupBanner = startup.status.map { StartupBannerUi(it.inProgress, it.message, it.warnings) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartupBannerUi())
    val spotifySettingsRequests = redirects.settingsRequests

    fun retryStartup() = startup.retry()
    fun togglePlayback() = engine.togglePlayPause()
    fun next() = engine.next()
    fun spotifySettingsOpened(uri: String) = redirects.settingsOpened(uri)
}
