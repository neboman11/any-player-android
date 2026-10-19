package com.anyplayer.android.ui.nowplaying

import androidx.compose.runtime.Immutable
import com.anyplayer.android.dj.PreparationStatus

/**
 * Where the next AI DJ break sits in "Up Next".
 *
 * [songsAway] is the number of upcoming songs before the break (0 = right after the current
 * song); null when no break is scheduled. The entry is shown only when [showEntries] is set.
 */
@Immutable
data class DjQueueHint(
    val showEntries: Boolean = false,
    val songsAway: Int? = null,
    val preparationStatus: PreparationStatus? = null,
)
