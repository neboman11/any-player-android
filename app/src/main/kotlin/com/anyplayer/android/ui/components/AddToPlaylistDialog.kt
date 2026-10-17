package com.anyplayer.android.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp

/** A STANDARD custom playlist a track can be added to. */
@Immutable
data class PlaylistChoice(val id: String, val name: String, val trackCount: Int)

/** Picker listing STANDARD custom playlists; tapping one adds [trackTitle] to it. */
@Composable
fun AddToPlaylistDialog(
    trackTitle: String,
    choices: List<PlaylistChoice>,
    onPick: (PlaylistChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add \"$trackTitle\" to playlist") },
        text = {
            if (choices.isEmpty()) {
                Text("No standard playlists yet. Create one in Playlists.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(choices, key = { it.id }) { choice ->
                        ListItem(
                            headlineContent = { Text(choice.name) },
                            supportingContent = { Text("${choice.trackCount} tracks") },
                            modifier = Modifier.clickable {
                                onPick(choice)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
