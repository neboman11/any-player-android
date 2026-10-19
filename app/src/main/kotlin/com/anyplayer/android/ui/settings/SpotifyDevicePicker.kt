package com.anyplayer.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.playback.spotify.PreferredSpotifyDevice
import com.anyplayer.android.playback.spotify.SpotifyCommandException
import com.anyplayer.android.playback.spotify.SpotifyDevice
import com.anyplayer.android.playback.spotify.SpotifyDevices
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SpotifyDevicePickerState(
    val preferred: PreferredSpotifyDevice? = null,
    val devices: List<SpotifyDevice> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** The saved device stays listed (marked offline) even when Spotify doesn't report it now. */
    val options: List<Pair<String, String>>
        get() {
            val online = devices.map { it.id to "${it.name} (${it.type})" }
            val saved = preferred?.takeIf { want -> devices.none { it.id == want.id || it.name == want.name } }
            return online + listOfNotNull(saved?.let { it.id to "${it.name} (offline)" })
        }

    val selectedLabel: String
        get() = preferred?.let { want ->
            devices.firstOrNull { it.id == want.id || it.name == want.name }?.let { "${it.name} (${it.type})" } ?: "${want.name} (offline)"
        } ?: AUTOMATIC

    companion object {
        const val AUTOMATIC = "Automatic (active device, else this phone)"
    }
}

@HiltViewModel
class SpotifyDevicePickerViewModel @Inject constructor(private val spotifyDevices: SpotifyDevices) : ViewModel() {
    private val listing = MutableStateFlow(SpotifyDevicePickerState())

    val state: StateFlow<SpotifyDevicePickerState> = combine(listing, spotifyDevices.preferred) { list, preferred ->
        list.copy(preferred = preferred)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpotifyDevicePickerState())

    fun refresh() {
        viewModelScope.launch {
            listing.update { it.copy(loading = true, error = null) }
            val result = try {
                Result.success(spotifyDevices.available())
            } catch (e: SpotifyCommandException) {
                Result.failure(e)
            }
            listing.update {
                it.copy(
                    loading = false,
                    devices = result.getOrDefault(it.devices),
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    /** [deviceId] null = automatic. */
    fun select(deviceId: String?) {
        val device = deviceId?.let { id -> listing.value.devices.firstOrNull { it.id == id } }
        if (deviceId != null && device == null) return // the offline saved entry: keep it as is
        viewModelScope.launch { spotifyDevices.setPreferred(device) }
    }
}

/** Settings → Providers → Spotify "Playback device" picker. Applies from the next Spotify start. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpotifyDevicePicker(viewModel: SpotifyDevicePickerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refresh() }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = state.selectedLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Playback device") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().testTag("spotify_device_picker"),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(SpotifyDevicePickerState.AUTOMATIC) }, onClick = {
                    viewModel.select(null)
                    expanded = false
                })
                state.options.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        viewModel.select(id)
                        expanded = false
                    })
                }
            }
        }
        OutlinedButton(onClick = viewModel::refresh, enabled = !state.loading) { Text(if (state.loading) "…" else "Refresh") }
    }
    when {
        state.error != null -> Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
        !state.loading && state.devices.isEmpty() ->
            Text("No Spotify devices online. Open Spotify on a device, then Refresh.", style = MaterialTheme.typography.bodySmall)
    }
}
