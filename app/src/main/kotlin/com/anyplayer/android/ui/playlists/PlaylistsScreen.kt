package com.anyplayer.android.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.ui.components.Artwork
import com.anyplayer.android.ui.components.ConfirmDialog
import com.anyplayer.android.ui.components.MessagesEffect
import com.anyplayer.android.ui.components.Pill
import com.anyplayer.android.ui.components.RefreshFeedback
import com.anyplayer.android.ui.components.ScreenPadding
import com.anyplayer.android.ui.components.SectionTitle
import com.anyplayer.android.ui.components.SourceBadge

@Composable
fun PlaylistsRoute(
    onOpenProvider: (SourceType, String) -> Unit,
    onOpenCustom: (String) -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MessagesEffect(viewModel.messages)
    PlaylistsScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onOpenProvider = { onOpenProvider(it.source, it.id) },
        onPlayProvider = viewModel::playProvider,
        onOpenCustom = onOpenCustom,
        onPlayCustom = viewModel::playCustom,
        onDeleteCustom = viewModel::delete,
        onCreate = viewModel::create,
    )
}

@Composable
fun PlaylistsScreen(
    state: PlaylistsUi,
    onRefresh: () -> Unit,
    onOpenProvider: (ProviderPlaylistItem) -> Unit,
    onPlayProvider: (ProviderPlaylistItem) -> Unit,
    onOpenCustom: (String) -> Unit,
    onPlayCustom: (String) -> Unit,
    onDeleteCustom: (String) -> Unit,
    onCreate: (String, PlaylistType) -> Unit,
) {
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "provider_header", contentType = "header") {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Provider playlists", modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = onRefresh, enabled = !state.refresh.inProgress) {
                    if (state.refresh.inProgress) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh")
                }
            }
        }
        item(key = "provider_refresh", contentType = "feedback") {
            RefreshFeedback(state.refresh.inProgress, state.refresh.status, "Refreshing playlists...")
        }
        if (state.loaded && state.providerPlaylists.isEmpty()) {
            item(key = "provider_empty", contentType = "text") { Text("No provider playlists loaded") }
        }
        items(state.providerPlaylists, key = { it.key }, contentType = { "provider" }) { item ->
            ElevatedCard(onClick = { onOpenProvider(item) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    leadingContent = { Artwork(item.imageUrl, 48.dp) },
                    headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SourceBadge(item.source)
                            Spacer(Modifier.width(8.dp))
                            Text("${item.trackCount} tracks", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = { onPlayProvider(item) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${item.name}")
                        }
                    },
                )
            }
        }

        item(key = "custom_header", contentType = "header") { SectionTitle("Local custom playlists") }
        item(key = "create_standard", contentType = "create") {
            CreatePlaylistRow(
                label = "New standard playlist",
                fieldTag = "field_new_standard_playlist_name",
                buttonTag = "create_standard_playlist_button",
                onCreate = { onCreate(it, PlaylistType.STANDARD) },
            )
        }
        item(key = "create_union", contentType = "create") {
            CreatePlaylistRow(
                label = "New union playlist",
                fieldTag = "field_new_union_playlist_name",
                buttonTag = "create_union_playlist_button",
                onCreate = { onCreate(it, PlaylistType.UNION) },
            )
        }
        if (state.loaded && state.customPlaylists.isEmpty()) {
            item(key = "custom_empty", contentType = "text") { Text("No custom playlists yet") }
        }
        items(state.customPlaylists, key = { "custom_${it.id}" }, contentType = { "custom" }) { item ->
            ElevatedCard(onClick = { onOpenCustom(item.id) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(item.type.name.lowercase())
                            Spacer(Modifier.width(8.dp))
                            Text("${item.trackCount} tracks", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { onPlayCustom(item.id) }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${item.name}")
                            }
                            IconButton(onClick = { pendingDelete = item.id }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete ${item.name}",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    pendingDelete?.let { id ->
        val name = state.customPlaylists.firstOrNull { it.id == id }?.name.orEmpty()
        ConfirmDialog(
            title = "Delete playlist?",
            text = "\"$name\" will be permanently deleted.",
            confirmLabel = "Delete",
            onConfirm = { onDeleteCustom(id) },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun CreatePlaylistRow(label: String, fieldTag: String, buttonTag: String, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val submit = {
        onCreate(name)
        name = ""
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f).testTag(fieldTag),
        )
        Button(onClick = submit, modifier = Modifier.testTag(buttonTag).padding(top = 6.dp)) { Text("Create") }
    }
}
