package com.anyplayer.android.ui.components

import androidx.lifecycle.SavedStateHandle
import com.anyplayer.android.core.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Filter + sort state of a [TrackTable], persisted in [SavedStateHandle] under [keyPrefix].
 * Rows are rebuilt on [computeDispatcher] whenever the tracks, filter or sort change.
 */
class TrackTableController(
    private val savedStateHandle: SavedStateHandle,
    private val keyPrefix: String,
    tracks: Flow<List<Track>>,
    scope: CoroutineScope,
    computeDispatcher: CoroutineDispatcher,
) {
    private val filterKey = "${keyPrefix}_filter"
    private val sortColumnKey = "${keyPrefix}_sort_column"
    private val sortAscendingKey = "${keyPrefix}_sort_ascending"

    /** Read synchronously by the filter text field. */
    val filter: StateFlow<String> = savedStateHandle.getStateFlow(filterKey, "")

    private val sort: Flow<TrackSort> = combine(
        savedStateHandle.getStateFlow<String?>(sortColumnKey, null),
        savedStateHandle.getStateFlow(sortAscendingKey, true),
    ) { column, ascending ->
        TrackSort(column?.let { name -> TrackSortColumn.entries.firstOrNull { it.name == name } }, ascending)
    }

    val table: StateFlow<TrackTableUi> = combine(tracks, sort, filter) { list, s, f -> Triple(list, s, f) }
        .map { (list, s, f) -> TrackTableUi(s, TrackTables.buildRows(list, s, f), list.size) }
        .flowOn(computeDispatcher)
        .stateIn(scope, SharingStarted.Eagerly, TrackTableUi())

    fun setFilter(value: String) {
        savedStateHandle[filterKey] = value
    }

    fun toggleSort(column: TrackSortColumn) {
        val current = TrackSort(
            savedStateHandle.get<String?>(sortColumnKey)?.let { name -> TrackSortColumn.entries.firstOrNull { it.name == name } },
            savedStateHandle.get<Boolean>(sortAscendingKey) ?: true,
        )
        val next = current.cycle(column)
        savedStateHandle[sortColumnKey] = next.column?.name
        savedStateHandle[sortAscendingKey] = next.ascending
    }
}
