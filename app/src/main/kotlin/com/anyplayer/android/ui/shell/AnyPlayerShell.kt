package com.anyplayer.android.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.anyplayer.android.ui.components.Artwork
import com.anyplayer.android.ui.components.LocalSnackbarHostState
import com.anyplayer.android.ui.nav.AnyPlayerNavigation
import com.anyplayer.android.ui.nav.MainDestination

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnyPlayerShell(viewModel: ShellViewModel = hiltViewModel()) {
    val controller = rememberNavController()
    val entry by controller.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val mini by viewModel.miniPlayer.collectAsStateWithLifecycle()
    val startup by viewModel.startupBanner.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var previousRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(route) {
        if (previousRoute == MainDestination.SETTINGS.route && route != MainDestination.SETTINGS.route) viewModel.leaveSettings()
        previousRoute = route
    }
    val navigate: (MainDestination) -> Unit = { destination ->
        controller.navigate(destination.route) {
            popUpTo(controller.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    LaunchedEffect(viewModel, controller) {
        viewModel.spotifyRedirects.collect {
            controller.navigate(MainDestination.SETTINGS.route) { launchSingleTop = true }
        }
    }
    CompositionLocalProvider(LocalSnackbarHostState provides snackbar) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Any Player") },
                    navigationIcon = {
                        if (route?.startsWith("playlists/") == true) {
                            IconButton(onClick = { controller.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Column {
                    if (route != MainDestination.NOW_PLAYING.route && mini.visible) {
                        MiniPlayer(mini, { navigate(MainDestination.NOW_PLAYING) }, viewModel::togglePlayback, viewModel::next)
                    }
                    NavigationBar {
                        MainDestination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = route == destination.route ||
                                    (destination == MainDestination.PLAYLISTS && route?.startsWith("playlists/") == true),
                                onClick = { navigate(destination) },
                                label = { Text(destination.label) },
                                icon = {
                                    Icon(
                                        when (destination) {
                                            MainDestination.NOW_PLAYING -> Icons.Filled.MusicNote
                                            MainDestination.PLAYLISTS -> Icons.AutoMirrored.Filled.PlaylistPlay
                                            MainDestination.SEARCH -> Icons.Filled.Search
                                            MainDestination.SETTINGS -> Icons.Filled.Settings
                                        },
                                        contentDescription = destination.label,
                                    )
                                },
                                modifier = Modifier.testTag("tab_${destination.name}"),
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (startup.visible) StartupBanner(startup, viewModel::retryStartup)
                AnyPlayerNavigation(controller, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StartupBanner(state: StartupBannerUi, retry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            state.message?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            state.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.warnings.isNotEmpty()) TextButton(onClick = retry, enabled = !state.busy) { Text("Retry Startup") }
        }
    }
}

@Composable
private fun MiniPlayer(state: MiniPlayerUi, open: () -> Unit, toggle: () -> Unit, next: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(state.imageUrl, 48.dp)
            Column(Modifier.weight(1f)) {
                Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(state.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = toggle, enabled = state.playEnabled) {
                Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.playing) "Pause" else "Play")
            }
            IconButton(onClick = next) { Icon(Icons.Filled.SkipNext, "Next") }
        }
    }
}
