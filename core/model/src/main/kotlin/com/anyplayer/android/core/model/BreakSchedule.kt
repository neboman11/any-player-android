package com.anyplayer.android.core.model

import kotlin.random.Random

/** [songsUntilBreak] counts real songs between the current song and the break's target.
 * A zero means the break plays on the next advance, before the next playable song. */
data class BreakSchedule(
    val enabled: Boolean = false,
    val songsUntilBreak: Int = 3,
    val anchorOrderPos: Int = -1,
    val orderVersion: Long = -1,
    val failedOnCurrent: Boolean = false,
    val targetUid: Long? = null,
) {
    fun songsAway(queue: QueueState): Int? {
        if (!enabled) return null
        val pos = targetUid?.let(queue::orderPosOfUid) ?: -1
        return if (pos > queue.cursor) (queue.cursor + 1 until pos).count { !queue.entryAt(it).track.isDjFiller }
        else songsUntilBreak
    }
}

sealed interface ScheduleEvent {
    data class Enabled(val enabled: Boolean) : ScheduleEvent
    data object QueueReplaced : ScheduleEvent
    data object CursorMoved : ScheduleEvent
    data object BreakStarted : ScheduleEvent
    data object GenerationFailed : ScheduleEvent
}

fun reduce(s: BreakSchedule, event: ScheduleEvent, queue: QueueState, random: Random): BreakSchedule {
    if (event is ScheduleEvent.Enabled && !event.enabled) return BreakSchedule()
    if (event is ScheduleEvent.Enabled && event.enabled == s.enabled) return s
    val reset = event is ScheduleEvent.Enabled || event == ScheduleEvent.QueueReplaced || event == ScheduleEvent.BreakStarted
    var next = if (reset) s.copy(
        enabled = if (event is ScheduleEvent.Enabled) event.enabled else s.enabled,
        songsUntilBreak = random.nextInt(3, 6), anchorOrderPos = queue.cursor,
        orderVersion = queue.contentVersion, failedOnCurrent = false,
    ) else s
    if (!next.enabled) return next.copy(targetUid = null)
    if (event == ScheduleEvent.GenerationFailed) {
        next = next.copy(songsUntilBreak = 3, anchorOrderPos = queue.cursor,
            orderVersion = queue.contentVersion, failedOnCurrent = true)
    } else if (!reset) {
        val reanchor = next.orderVersion != queue.contentVersion || next.anchorOrderPos < 0 || queue.cursor < next.anchorOrderPos - 1
        val delta = if (reanchor || queue.cursor < 0) 0 else
            (next.anchorOrderPos + 1..queue.cursor).count { !queue.entryAt(it).track.isDjFiller }
        val remaining = next.songsUntilBreak - delta
        next = next.copy(
            songsUntilBreak = if (remaining < 0) 3 else remaining,
            anchorOrderPos = if (reanchor) queue.cursor else maxOf(next.anchorOrderPos, queue.cursor), orderVersion = queue.contentVersion,
            failedOnCurrent = if (remaining < 0) true else if (delta > 0) false else next.failedOnCurrent,
        )
    }
    var realSongs = 0
    var target: Long? = null
    if (queue.cursor >= 0) for (pos in maxOf(next.anchorOrderPos, queue.cursor) + 1..queue.order.lastIndex) {
        val entry = queue.entryAt(pos)
        if (entry.track.isDjFiller) continue
        if (realSongs >= next.songsUntilBreak && backendFor(entry.track) != BackendKind.UNPLAYABLE) {
            target = entry.uid
            break
        }
        realSongs++
    }
    return next.copy(targetUid = target)
}
