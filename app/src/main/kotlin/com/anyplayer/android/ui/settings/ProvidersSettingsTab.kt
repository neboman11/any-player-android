package com.anyplayer.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType

@Composable
internal fun ProvidersSettingsTab(viewModel: ProvidersSettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.feedback.isNotBlank()) Text(state.feedback)
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    ProviderHeading("Spotify", state.profiles[SourceType.SPOTIFY])
    val spotify = state.profiles[SourceType.SPOTIFY]
    Button(onClick = { if (spotify?.connected == true) viewModel.disconnect(SourceType.SPOTIFY) else viewModel.linkSpotify() }, enabled = !state.busy) {
        Text(if (spotify?.connected == true) "Disconnect Spotify" else "Link Spotify Account")
    }
    spotify?.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (spotify?.hasToken == true) SpotifyDevicePicker()
    listOf(SourceType.JELLYFIN, SourceType.PLEX).forEach { source ->
        HorizontalDivider()
        val jellyfin = source == SourceType.JELLYFIN
        val label = if (jellyfin) "Jellyfin" else "Plex"
        val prefix = if (jellyfin) "jellyfin" else "plex"
        val fields = if (jellyfin) state.jellyfin else state.plex
        val profile = state.profiles[source]
        ProviderHeading(label, profile)
        SettingsField(fields.url, { value -> viewModel.edit(source) { it.copy(url = value) } }, "$label URL", Modifier.testTag("field_${prefix}_url"))
        SettingsField(fields.token, { value -> viewModel.edit(source) { it.copy(token = value) } }, if (jellyfin) "Jellyfin API Key" else "Plex Token", Modifier.testTag("field_${prefix}_token"), secret = true)
        SettingsField(fields.pageSize, { viewModel.updatePageSize(source, it) }, "Playlist Page Size", number = true, placeholder = "300", saved = fields.pageSizeSaved)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.connect(source) }, enabled = !state.busy) { Text("Connect $label") }
            if (profile?.connected == true) OutlinedButton(onClick = { viewModel.disconnect(source) }, enabled = !state.busy) { Text("Disconnect") }
        }
        profile?.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    HorizontalDivider()
    OutlinedButton(onClick = viewModel::clearCache, enabled = !state.busy) { Text("Clear Provider Cache") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderHeading(label: String, profile: ProviderConnectionProfile?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        if (profile?.connected == true) {
            val tooltip = rememberTooltipState()
            TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(providerConnectionTooltip(profile)) } }, state = tooltip) {
                Text("✓", modifier = Modifier.testTag("${label}_connected").semantics {
                    contentDescription = providerConnectionTooltip(profile)
                })
            }
        }
    }
}
