package com.anyplayer.android.core.model

import kotlin.random.Random

/** One queue slot. [uid] is unique within the queue even when the same track appears twice. */
data class QueueEntry(val uid: Long, val track: Track)

/**
 * The app-owned queue for every source mix.
 *
 * [entries] is insertion order, [order] holds indices into [entries] in play order and [cursor]
 * is a position in [order]. With shuffle off [order] is always the identity permutation.
 * [contentVersion] bumps only when [entries] or [order] change; cursor moves do not bump it.
 */
data class QueueState(
    val entries: List<QueueEntry> = emptyList(),
    val order: List<Int> = emptyList(),
    val cursor: Int = -1,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val playNextOffset: Int = 0,
    val contentVersion: Long = 0,
    val nextUid: Long = 1,
) {
    val current: QueueEntry?
        get() = if (cursor in order.indices) entries[order[cursor]] else null

    fun entryAt(orderPos: Int): QueueEntry = entries[order[orderPos]]

    fun upcoming(): List<QueueEntry> =
        if (cursor < 0) order.map(entries::get) else order.drop(cursor + 1).map(entries::get)

    fun history(): List<QueueEntry> = order.take(cursor.coerceAtLeast(0)).map(entries::get)

    fun orderPosOfUid(uid: Long): Int = order.indexOfFirst { entries[it].uid == uid }
}

/** Pure queue transitions. */
object QueueOps {

    /**
     * Replaces the queue with [tracks], current = `tracks[startIndex]`. With shuffle on the
     * current entry is placed first and the rest shuffled. [randomStart] (user-initiated play
     * with `startIndex == 0`) picks a random start when shuffle is on and there is more than
     * one track; restore/sync paths keep the given start.
     */
    fun replace(
        s: QueueState,
        tracks: List<Track>,
        startIndex: Int,
        random: Random,
        randomStart: Boolean = false,
    ): QueueState {
        val entries = tracks.mapIndexed { i, t -> QueueEntry(s.nextUid + i, t) }
        val base = s.copy(
            entries = entries,
            playNextOffset = 0,
            contentVersion = s.contentVersion + 1,
            nextUid = s.nextUid + tracks.size,
        )
        if (tracks.isEmpty()) return base.copy(order = emptyList(), cursor = -1)
        val start = if (randomStart && s.shuffle && startIndex == 0 && tracks.size > 1) {
            random.nextInt(tracks.size)
        } else {
            startIndex.coerceIn(0, tracks.lastIndex)
        }
        return if (s.shuffle) {
            val order = listOf(start) + (tracks.indices).filter { it != start }.shuffled(random)
            base.copy(order = order, cursor = 0)
        } else {
            base.copy(order = tracks.indices.toList(), cursor = start)
        }
    }

    /**
     * Inserts [track] after the current entry + [QueueState.playNextOffset] in insertion order
     * and after the cursor + offset in play order, so consecutive "play next" calls stack in
     * call order. An empty queue becomes a single current entry.
     */
    fun playNext(s: QueueState, track: Track): QueueState {
        val entry = QueueEntry(s.nextUid, track)
        if (s.entries.isEmpty()) {
            return s.copy(
                entries = listOf(entry),
                order = listOf(0),
                cursor = 0,
                playNextOffset = 0,
                contentVersion = s.contentVersion + 1,
                nextUid = s.nextUid + 1,
            )
        }
        val orderInsert = (s.cursor + 1 + s.playNextOffset).coerceIn(0, s.order.size)
        val currentEntryIndex = if (s.cursor in s.order.indices) s.order[s.cursor] else -1
        val entryInsert = (currentEntryIndex + 1 + s.playNextOffset).coerceIn(0, s.entries.size)
        val entries = s.entries.toMutableList().apply { add(entryInsert, entry) }
        val order = s.order.map { if (it >= entryInsert) it + 1 else it }.toMutableList()
            .apply { add(orderInsert, entryInsert) }
        return s.copy(
            entries = entries,
            order = order,
            playNextOffset = s.playNextOffset + 1,
            contentVersion = s.contentVersion + 1,
            nextUid = s.nextUid + 1,
        )
    }

    /** Moves to the next play-order position; repeat ALL wraps when [wrap]. Null at the end. */
    fun advance(s: QueueState, wrap: Boolean = true): QueueState? {
        if (s.order.isEmpty()) return null
        val next = when {
            s.cursor + 1 < s.order.size -> s.cursor + 1
            wrap && s.repeat == RepeatMode.ALL -> 0
            else -> return null
        }
        return moveCursor(s, next)
    }

    /** Moves to the previous play-order position; null at the start. */
    fun back(s: QueueState): QueueState? =
        if (s.cursor > 0) moveCursor(s, s.cursor - 1) else null

    /** Makes the entry with [uid] current; unknown uid → unchanged state. */
    fun jumpToUid(s: QueueState, uid: Long): QueueState {
        val pos = s.orderPosOfUid(uid)
        return if (pos < 0) s else moveCursor(s, pos)
    }

    /**
     * On: the current entry stays current and keeps its play-order position; history entries
     * before the cursor are kept, every other entry is shuffled after it.
     * Off: order becomes the identity and the cursor follows the current entry.
     */
    fun setShuffle(s: QueueState, on: Boolean, random: Random): QueueState {
        if (on == s.shuffle) return s
        val currentEntry = if (s.cursor in s.order.indices) s.order[s.cursor] else -1
        val order = if (on) {
            if (currentEntry < 0) {
                s.entries.indices.shuffled(random)
            } else {
                s.order.take(s.cursor) + currentEntry + s.order.drop(s.cursor + 1).shuffled(random)
            }
        } else {
            s.entries.indices.toList()
        }
        return s.copy(
            shuffle = on,
            order = order,
            cursor = if (currentEntry < 0) -1 else order.indexOf(currentEntry),
            playNextOffset = 0,
            contentVersion = s.contentVersion + 1,
        )
    }

    fun setRepeat(s: QueueState, mode: RepeatMode): QueueState =
        if (mode == s.repeat) s else s.copy(repeat = mode)

    /** Cursor moves reset the "play next" stacking and never bump [QueueState.contentVersion]. */
    private fun moveCursor(s: QueueState, pos: Int): QueueState = s.copy(cursor = pos, playNextOffset = 0)
}
