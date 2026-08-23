package com.anyplayer.android.feature.playback.service

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.anyplayer.android.core.model.Track

@UnstableApi
internal class QueueTimeline(
    private val tracks: List<Track>
) : Timeline() {
    override fun getWindowCount(): Int = tracks.size

    override fun getPeriodCount(): Int = tracks.size

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        val track = tracks[windowIndex]
        val durationUs = track.durationMs
            ?.takeIf { it > 0 }
            ?.let { it * 1_000L }
            ?: C.TIME_UNSET
        return window.set(
            Window.SINGLE_WINDOW_UID,
            track.toMediaItem(),
            null,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            false,
            true,
            null,
            0,
            durationUs,
            0,
            0,
            0
        )
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        val id = tracks[periodIndex].id
        return period.set(
            if (setIds) id else null,
            if (setIds) id else null,
            periodIndex,
            C.TIME_UNSET,
            0
        )
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        val stringUid = uid as? String ?: return C.INDEX_UNSET
        val index = tracks.indexOfFirst { it.id == stringUid }
        return if (index >= 0) index else C.INDEX_UNSET
    }

    override fun getUidOfPeriod(periodIndex: Int): Any = tracks[periodIndex].id
}
