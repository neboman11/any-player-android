package com.anyplayer.android.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class TabSection { NOW_PLAYING, PLAYLISTS, SEARCH, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnyPlayerApp(viewModel: MainViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val redirectUri by SpotifyAuthRedirectBus.redirectUri.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var currentTab by remember { mutableStateOf(TabSection.NOW_PLAYING) }

    LaunchedEffect(state.spotifyAuthLaunchUrl) {
        val url = state.spotifyAuthLaunchUrl ?: return@LaunchedEffect
        uriHandler.openUri(url)
        viewModel.markSpotifyAuthLaunchHandled()
    }

    LaunchedEffect(redirectUri) {
        val callback = redirectUri ?: return@LaunchedEffect
        viewModel.completeSpotifyLink(callback)
        SpotifyAuthRedirectBus.clear()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Any Player") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = state.startupMessage, style = MaterialTheme.typography.bodyMedium)
            if (state.startupWarnings.isNotEmpty()) {
                state.startupWarnings.forEach { warning ->
                    Text(text = warning, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.startupCanRetry || state.startupCanContinueWithoutProvider) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.startupCanRetry) {
                        Button(onClick = viewModel::retryStartup, enabled = !state.startupInProgress) {
                            Text("Retry Startup")
                        }
                    }
                    if (state.startupCanContinueWithoutProvider) {
                        Button(onClick = viewModel::continueWithoutProviderStartup, enabled = !state.startupInProgress) {
                            Text("Continue Without Provider")
                        }
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TabSection.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        shape = SegmentedButtonDefaults.itemShape(index, TabSection.entries.size)
                    ) {
                        Text(
                            when (tab) {
                                TabSection.NOW_PLAYING -> "Now Playing"
                                TabSection.PLAYLISTS -> "Playlists"
                                TabSection.SEARCH -> "Search"
                                TabSection.SETTINGS -> "Settings"
                            }
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (currentTab) {
                    TabSection.NOW_PLAYING -> NowPlayingSection(viewModel, state)
                    TabSection.PLAYLISTS -> PlaylistSection(viewModel, state)
                    TabSection.SEARCH -> SearchSection(viewModel, state)
                    TabSection.SETTINGS -> SettingsSection(viewModel, state)
                }
            }
        }
    }
}
