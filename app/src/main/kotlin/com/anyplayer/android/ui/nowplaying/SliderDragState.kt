package com.anyplayer.android.ui.nowplaying

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Local drag value of a slider: the thumb follows the finger while the source of truth is
 * untouched, and the final value is committed once in `onValueChangeFinished`.
 */
@Stable
class SliderDragState {
    /** Non-null while dragging; shown instead of the external value. */
    var dragValue: Float? by mutableStateOf(null)
        private set

    fun onValueChange(value: Float) {
        dragValue = value
    }

    fun onValueChangeFinished(commit: (Float) -> Unit) {
        val value = dragValue ?: return
        dragValue = null
        commit(value)
    }

    fun displayValue(external: Float): Float = dragValue ?: external
}
