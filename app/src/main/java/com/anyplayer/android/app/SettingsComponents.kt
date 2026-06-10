package com.anyplayer.android.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.anyplayer.android.core.model.DuplicateGroup
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track

internal enum class ProviderSortColumn { PROVIDER, STATUS, TIER, PLAYBACK }

@Composable
internal fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide" else "Show"
                )
            }
        },
        modifier = modifier
    )
}

@Composable
internal fun ProviderConnectionHeader(name: String, feedback: String?, connecting: Boolean) {
    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (!feedback.isNullOrBlank()) {
        Text(feedback, style = MaterialTheme.typography.bodySmall)
    }
    if (connecting) {
        Text("Connecting…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun DuplicateGroupsSection(
    title: String,
    tracks: List<Track>,
    duplicateGroups: List<DuplicateGroup>,
    showRemoveControls: Boolean = false,
    onRemoveDuplicate: ((Int) -> Unit)? = null
) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        duplicateGroups.forEach { group ->
            val keeperTrack = tracks.getOrNull(group.firstOccurrenceIndex)
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Key: ${group.key}")
                    keeperTrack?.let { track ->
                        Text("Kept playback entry:")
                        TrackRow(
                            track = track,
                            indexLabel = "${group.firstOccurrenceIndex + 1}.",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (group.occurrences.isNotEmpty()) {
                        Text("Duplicate occurrences:")
                        group.occurrences.forEach { occurrence ->
                            val duplicateTrack = tracks.getOrNull(occurrence.index) ?: return@forEach
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TrackRow(
                                    track = duplicateTrack,
                                    indexLabel = "${occurrence.index + 1}.",
                                    modifier = Modifier.weight(1f)
                                )
                                if (showRemoveControls && onRemoveDuplicate != null) {
                                    androidx.compose.material3.Button(onClick = { onRemoveDuplicate(occurrence.index) }) {
                                        Text("Remove")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkflowCard(title: String, description: String, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun WorkflowStep(number: Int, label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .padding(0.dp)
                    .then(
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    ),
            )
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        }
        content()
    }
}

@Composable
internal fun ProviderStatusTable(
    providerStatuses: List<ProviderConnectionProfile>,
    onDisconnect: (SourceType) -> Unit
) {
    var sortColumnName by rememberSaveable("settings_provider_sort_column") { mutableStateOf<String?>(null) }
    var sortAscending by rememberSaveable("settings_provider_sort_ascending") { mutableStateOf(true) }
    val sortColumn = sortColumnName?.let { currentSortName ->
        ProviderSortColumn.entries.firstOrNull { it.name == currentSortName }
    }
    val sortedProviderStatuses = remember(providerStatuses, sortColumnName, sortAscending) {
        if (sortColumn == null) {
            return@remember providerStatuses
        }

        providerStatuses.sortedWith { left, right ->
            val comparison = compareProviderStatuses(left, right, sortColumn)
            if (sortAscending) comparison else -comparison
        }
    }

    fun requestSort(column: ProviderSortColumn) {
        if (sortColumn != column) {
            sortColumnName = column.name
            sortAscending = true
            return
        }

        if (sortAscending) {
            sortAscending = false
            return
        }

        sortColumnName = null
        sortAscending = true
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderSortableHeader(
                    label = "Provider",
                    isActive = sortColumn == ProviderSortColumn.PROVIDER,
                    ascending = sortAscending,
                    modifier = Modifier.weight(1.2f),
                    onClick = { requestSort(ProviderSortColumn.PROVIDER) }
                )
                ProviderSortableHeader(
                    label = "Status",
                    isActive = sortColumn == ProviderSortColumn.STATUS,
                    ascending = sortAscending,
                    modifier = Modifier.weight(1f),
                    onClick = { requestSort(ProviderSortColumn.STATUS) }
                )
                ProviderSortableHeader(
                    label = "Tier",
                    isActive = sortColumn == ProviderSortColumn.TIER,
                    ascending = sortAscending,
                    modifier = Modifier.weight(1f),
                    onClick = { requestSort(ProviderSortColumn.TIER) }
                )
                ProviderSortableHeader(
                    label = "Playback",
                    isActive = sortColumn == ProviderSortColumn.PLAYBACK,
                    ascending = sortAscending,
                    modifier = Modifier.weight(1f),
                    onClick = { requestSort(ProviderSortColumn.PLAYBACK) }
                )
                Text("Action", style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider()

            sortedProviderStatuses.forEachIndexed { index, status ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = providerDisplayName(status.source),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1.2f)
                        )
                        Text(
                            text = if (status.connected) "Connected" else "Disconnected",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = when (status.isPremium) {
                                true -> "Premium"
                                false -> "Free"
                                null -> "—"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = when (status.playbackReady) {
                                true -> "Ready"
                                false -> "Needs Init"
                                null -> "—"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        if (status.connected) {
                            TextButton(onClick = { onDisconnect(status.source) }) {
                                Text("Disconnect")
                            }
                        } else {
                            Text("—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (!status.lastError.isNullOrBlank()) {
                        Text(
                            text = status.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (index != sortedProviderStatuses.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
internal fun SettingsProviderTabLabel(
    label: String,
    connected: Boolean,
    tooltipText: String
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        if (connected) {
            ProviderCheckmarkTooltip(tooltipText = tooltipText)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderCheckmarkTooltip(tooltipText: String) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(tooltipText)
            }
        },
        state = rememberTooltipState()
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = "Connected",
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

internal fun providerConnectionTooltip(status: ProviderConnectionProfile): String {
    if (!status.connected) {
        return "Not connected"
    }

    if (status.source == SourceType.SPOTIFY) {
        val tierLabel = when (status.isPremium) {
            true -> "Premium"
            false -> "Free"
            null -> "Tier unknown"
        }
        val playbackLabel = when (status.playbackReady) {
            true -> "Playback ready"
            false -> "Playback setup needed"
            null -> "Playback status unknown"
        }
        return "Connected • $tierLabel • $playbackLabel"
    }

    val serverPart = status.serverUrl?.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""
    return "Connected$serverPart"
}

internal fun providerDisplayName(sourceType: SourceType): String = when (sourceType) {
    SourceType.JELLYFIN -> "Jellyfin"
    SourceType.PLEX -> "Plex"
    SourceType.SPOTIFY -> "Spotify"
    SourceType.CUSTOM -> "Custom"
    SourceType.ALL -> "All"
}

private fun providerTierRank(status: ProviderConnectionProfile): Int = when (status.isPremium) {
    true -> 2
    false -> 1
    null -> 0
}

private fun providerPlaybackRank(status: ProviderConnectionProfile): Int = when (status.playbackReady) {
    true -> 2
    false -> 1
    null -> 0
}

private fun compareProviderStatuses(
    left: ProviderConnectionProfile,
    right: ProviderConnectionProfile,
    sortColumn: ProviderSortColumn
): Int = when (sortColumn) {
    ProviderSortColumn.PROVIDER -> providerDisplayName(left.source).compareTo(providerDisplayName(right.source), ignoreCase = true)
    ProviderSortColumn.STATUS -> (if (left.connected) 1 else 0).compareTo(if (right.connected) 1 else 0)
    ProviderSortColumn.TIER -> providerTierRank(left).compareTo(providerTierRank(right))
    ProviderSortColumn.PLAYBACK -> providerPlaybackRank(left).compareTo(providerPlaybackRank(right))
}

@Composable
private fun ProviderSortableHeader(
    label: String,
    isActive: Boolean,
    ascending: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            text = if (isActive) "$label ${if (ascending) "↑" else "↓"}" else label,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
