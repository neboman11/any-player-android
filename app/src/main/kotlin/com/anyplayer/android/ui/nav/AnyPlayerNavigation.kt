package com.anyplayer.android.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.ui.nowplaying.NowPlayingRoute
import com.anyplayer.android.ui.playlists.CustomPlaylistRoute
import com.anyplayer.android.ui.playlists.PlaylistsRoute
import com.anyplayer.android.ui.playlists.ProviderPlaylistRoute
import com.anyplayer.android.ui.search.SearchRoute
import com.anyplayer.android.ui.settings.SettingsScreen

enum class MainDestination(val route: String, val label: String) {
    NOW_PLAYING("now_playing", "Now Playing"),
    PLAYLISTS("playlists", "Playlists"),
    SEARCH("search", "Search"),
    SETTINGS("settings", "Settings"),
}

object PlaylistRoutes {
    const val PROVIDER = "playlists/provider/{source}/{id}"
    const val CUSTOM = "playlists/custom/{id}"
    fun provider(source: SourceType, id: String) = "playlists/provider/${source.name}/${Uri.encode(id)}"
    fun custom(id: String) = "playlists/custom/${Uri.encode(id)}"
}

@Composable
fun AnyPlayerNavigation(controller: NavHostController, modifier: Modifier = Modifier) {
    NavHost(controller, startDestination = MainDestination.NOW_PLAYING.route, modifier = modifier) {
        composable(MainDestination.NOW_PLAYING.route) { NowPlayingRoute() }
        composable(MainDestination.PLAYLISTS.route) {
            PlaylistsRoute(
                onOpenProvider = { source, id -> controller.navigate(PlaylistRoutes.provider(source, id)) },
                onOpenCustom = { controller.navigate(PlaylistRoutes.custom(it)) },
            )
        }
        composable(PlaylistRoutes.PROVIDER) { ProviderPlaylistRoute() }
        composable(PlaylistRoutes.CUSTOM) { CustomPlaylistRoute() }
        composable(MainDestination.SEARCH.route) { SearchRoute() }
        composable(MainDestination.SETTINGS.route) { SettingsScreen(Modifier) }
    }
}
