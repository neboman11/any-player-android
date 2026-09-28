package com.anyplayer.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.anyplayer.android.app.MainUiState
import com.anyplayer.android.app.MainViewModel
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType

@Composable
internal fun ProvidersSettingsTab(
    viewModel: MainViewModel,
    state: MainUiState,
    spotifyStatus: ProviderConnectionProfile,
    jellyfinStatus: ProviderConnectionProfile,
    plexStatus: ProviderConnectionProfile
) {
    if (!state.providerConnectionFeedback.isNullOrBlank()) {
        Text(state.providerConnectionFeedback, style = MaterialTheme.typography.bodySmall)
    }
    if (state.providerConnectionInProgress) {
        Text("Connecting…", style = MaterialTheme.typography.bodySmall)
    }

    ProviderHeading(name = "Spotify", status = spotifyStatus)
    Button(
        onClick = {
            if (spotifyStatus.connected) viewModel.disconnect(SourceType.SPOTIFY)
            else viewModel.beginSpotifyLink()
        },
        enabled = !state.providerConnectionInProgress
    ) {
        Text(if (spotifyStatus.connected) "Disconnect Spotify" else "Link Spotify Account")
    }
    spotifyStatus.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
        Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    HorizontalDivider()
    ProviderHeading(name = "Jellyfin", status = jellyfinStatus)
    OutlinedTextField(
        value = state.jellyfinUrlInput,
        onValueChange = viewModel::updateJellyfinUrlInput,
        label = { Text("Jellyfin URL") },
        modifier = Modifier.fillMaxWidth().testTag("field_jellyfin_url")
    )
    SecretTextField(
        value = state.jellyfinTokenInput,
        onValueChange = viewModel::updateJellyfinTokenInput,
        label = "Jellyfin API Key",
        modifier = Modifier.fillMaxWidth().testTag("field_jellyfin_token")
    )
    OutlinedTextField(
        value = state.jellyfinPlaylistPageSizeInput,
        onValueChange = viewModel::updateJellyfinPlaylistPageSizeInput,
        label = { Text("Playlist Page Size") },
        placeholder = { Text("300") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 1,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        trailingIcon = {
            if (state.jellyfinPageSizeSaved) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Saved",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { viewModel.connectJellyfin(state.jellyfinUrlInput, state.jellyfinTokenInput) },
            enabled = !state.providerConnectionInProgress
        ) { Text("Connect Jellyfin") }
        if (jellyfinStatus.connected) {
            OutlinedButton(
                onClick = { viewModel.disconnect(SourceType.JELLYFIN) },
                enabled = !state.providerConnectionInProgress
            ) { Text("Disconnect") }
        }
    }
    jellyfinStatus.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
        Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    HorizontalDivider()
    ProviderHeading(name = "Plex", status = plexStatus)
    OutlinedTextField(
        value = state.plexUrlInput,
        onValueChange = viewModel::updatePlexUrlInput,
        label = { Text("Plex URL") },
        modifier = Modifier.fillMaxWidth().testTag("field_plex_url")
    )
    SecretTextField(
        value = state.plexTokenInput,
        onValueChange = viewModel::updatePlexTokenInput,
        label = "Plex Token",
        modifier = Modifier.fillMaxWidth().testTag("field_plex_token")
    )
    OutlinedTextField(
        value = state.plexPlaylistPageSizeInput,
        onValueChange = viewModel::updatePlexPlaylistPageSizeInput,
        label = { Text("Playlist Page Size") },
        placeholder = { Text("300") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 1,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        trailingIcon = {
            if (state.plexPageSizeSaved) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Saved",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { viewModel.connectPlex(state.plexUrlInput, state.plexTokenInput) },
            enabled = !state.providerConnectionInProgress
        ) { Text("Connect Plex") }
        if (plexStatus.connected) {
            OutlinedButton(
                onClick = { viewModel.disconnect(SourceType.PLEX) },
                enabled = !state.providerConnectionInProgress
            ) { Text("Disconnect") }
        }
    }
    plexStatus.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
        Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    HorizontalDivider()
    Button(
        onClick = viewModel::clearProviderCaches,
        enabled = !state.providerConnectionInProgress
    ) {
        Text("Clear Provider Cache")
    }
}

@Composable
private fun ProviderHeading(name: String, status: ProviderConnectionProfile) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (status.connected) {
            ProviderCheckmarkTooltip(providerConnectionTooltip(status))
        }
    }
}
