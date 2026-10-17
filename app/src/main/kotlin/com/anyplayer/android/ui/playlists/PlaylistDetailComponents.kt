package com.anyplayer.android.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anyplayer.android.ui.components.LocalSnackbarHostState
import com.anyplayer.android.ui.components.SectionTitle
import com.anyplayer.android.ui.components.TrackRowUi
import kotlinx.coroutines.launch

/** "Tracks: N" with a Copy button putting `title\tartist\talbum` lines on the clipboard. */
@Composable
internal fun TrackCountRow(count: Int, clipboardText: suspend () -> String) {
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Tracks: $count", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(
            enabled = count > 0,
            onClick = {
                scope.launch {
                    clipboard.setText(AnnotatedString(clipboardText()))
                    snackbar.showSnackbar("Copied $count tracks")
                }
            },
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("Copy tracks", modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
internal fun DistinctRow(distinct: Boolean, onDistinctChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Distinct playback", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Skip repeated title + artist when playing",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = distinct,
            onCheckedChange = onDistinctChange,
            modifier = Modifier.semantics { contentDescription = "Distinct playback" },
        )
    }
}

@Composable
internal fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, modifier = Modifier.padding(start = 10.dp))
    }
}

/** Duplicate groups as lazy items; remove controls only when [onRemove] is non-null (STANDARD). */
internal fun LazyListScope.duplicateGroupItems(groups: List<DuplicateGroupUi>, onRemove: ((Int) -> Unit)?) {
    if (groups.isEmpty()) return
    item(key = "duplicates_title", contentType = "title") { SectionTitle("Duplicates") }
    groups.forEach { group ->
        item(key = "duplicates_${group.key}", contentType = "duplicate_header") {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("Kept playback entry:", style = MaterialTheme.typography.labelMedium)
                DuplicateLine(group.kept)
                Text("Duplicate occurrences:", style = MaterialTheme.typography.labelMedium)
            }
        }
        items(group.duplicates, key = { "duplicate_${it.originalIndex}" }, contentType = { "duplicate_row" }) { row ->
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                DuplicateLine(row, modifier = Modifier.weight(1f))
                if (onRemove != null) {
                    TextButton(onClick = { onRemove(row.originalIndex) }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateLine(row: TrackRowUi, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(row.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            row.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
