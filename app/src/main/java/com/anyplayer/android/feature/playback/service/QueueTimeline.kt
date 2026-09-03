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
        // Uid is the window index, not the track id: the queue can contain the same
        // track more than once, and two windows sharing an id would collapse together
        // in MediaSession/Android Auto queue continuity. Matches getUidOfPeriod.
        return window.set(
            windowIndex,
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
            windowIndex,
            windowIndex,
            0
        )
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        val id = tracks[periodIndex].id
        val uid: Any = periodIndex
        return period.set(
            if (setIds) id else null,
            if (setIds) uid else null,
            periodIndex,
            C.TIME_UNSET,
            0
        )
    }

    // Uids are the period index rather than the track id: the queue can contain
    // duplicate track ids (a track added to the queue more than once), and two
    // periods sharing an id would collapse to the same index here.
    override fun getIndexOfPeriod(uid: Any): Int {
        val index = uid as? Int ?: return C.INDEX_UNSET
        return if (index in tracks.indices) index else C.INDEX_UNSET
    }

    override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
}
