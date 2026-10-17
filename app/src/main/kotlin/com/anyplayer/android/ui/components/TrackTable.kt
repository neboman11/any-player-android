package com.anyplayer.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared track list: optional filter field, sortable header and a lazy list keyed by the
 * original index. [leadingContent] adds screen-specific items above the table in the same list.
 */
@Composable
fun TrackTable(
    table: TrackTableUi,
    actions: List<TrackRowAction>,
    onAction: (TrackRowAction, TrackRowUi) -> Unit,
    onSortClick: (TrackSortColumn) -> Unit,
    modifier: Modifier = Modifier,
    filter: String? = null,
    onFilterChange: (String) -> Unit = {},
    emptyText: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
    leadingContent: LazyListScope.() -> Unit = {},
) {
    LazyColumn(
        state = rememberLazyListState(),
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        leadingContent()
        if (table.totalCount == 0 && emptyText != null) {
            item(key = "track_table_empty", contentType = "empty") {
                Text(emptyText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
            }
            return@LazyColumn
        }
        if (filter != null) {
            item(key = "track_table_filter", contentType = "filter") {
                OutlinedTextField(
                    value = filter,
                    onValueChange = onFilterChange,
                    label = { Text("Search tracks") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = if (filter.isNotEmpty()) {
                        {
                            IconButton(onClick = { onFilterChange("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(key = "track_table_header", contentType = "header") {
            TrackSortHeader(sort = table.sort, onSortClick = onSortClick)
        }
        items(table.rows, key = { it.originalIndex }, contentType = { "track" }) { row ->
            TrackRow(row = row, actions = actions, onAction = onAction)
        }
    }
}

@Composable
private fun TrackSortHeader(sort: TrackSort, onSortClick: (TrackSortColumn) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TrackSortColumn.entries.forEach { column ->
                val active = sort.column == column
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSortClick(column) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = column.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (active) {
                        Icon(
                            imageVector = if (sort.ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            contentDescription = if (sort.ascending) "Sorted ascending" else "Sorted descending",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun TrackRow(row: TrackRowUi, actions: List<TrackRowAction>, onAction: (TrackRowAction, TrackRowUi) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.durationText, style = MaterialTheme.typography.labelMedium)
            SourceBadge(row.track.source)
        }
        if (actions.isNotEmpty()) {
            RowActionMenu(actions = actions, onAction = { onAction(it, row) })
        }
    }
}
