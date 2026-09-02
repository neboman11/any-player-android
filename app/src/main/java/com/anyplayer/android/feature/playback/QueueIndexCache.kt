package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.Track

/**
 * Owns [PlaybackQueueManager]'s track-ID lookup caches and the Spotify queue-position
 * cursor. [spotifyCurrentQueueIndex] is written directly by [PlaybackQueueManager] at
 * every point the Spotify queue advances, since that cursor is updated inline with
 * many different playback transitions rather than derived from a single source.
 */
internal class QueueIndexCache(
    private val spotifyPlaybackController: SpotifyPlaybackController
) {
    var spotifyCurrentQueueIndex: Int = 0

    // rebuildQueueCaches() writes these on the Main dispatcher (called from
    // PlaybackQueueManager/LocalPlaybackOps), but PlaybackQueueManager.persistState()
    // reads findQueueIndex()/cachedQueueTrackIds from a Dispatchers.IO coroutine -
    // @Volatile guarantees the IO thread sees the latest rebuilt reference instead of
    // a stale one, since there's no other synchronization between the two dispatchers.

    /** O(1) track-ID → queue-index lookup; rebuilt by [rebuildQueueCaches]. */
    @Volatile
    private var trackIdIndexMap: Map<String, Int> = emptyMap()

    /** Cached list of track IDs matching the current queue order; rebuilt by [rebuildQueueCaches]. */
    @Volatile
    var cachedQueueTrackIds: List<String> = emptyList()
        private set

    fun rebuildQueueCaches(queue: List<Track>) {
        val idMap = HashMap<String, Int>(queue.size * 2)
        val idList = ArrayList<String>(queue.size)
        queue.forEachIndexed { index, track ->
            val rawId = track.id
            idList.add(rawId)
            idMap.putIfAbsent(rawId, index)
            val normalized = normalizeSpotifyTrackId(rawId)
            if (normalized != rawId) {
                idMap.putIfAbsent(normalized, index)
            }
        }
        trackIdIndexMap = idMap
        cachedQueueTrackIds = idList
    }

    fun findQueueIndex(trackId: String): Int {
        trackIdIndexMap[trackId]?.let { return it }
        trackIdIndexMap[normalizeSpotifyTrackId(trackId)]?.let { return it }
        return -1
    }

    fun findQueueIndexNear(trackId: String, preferredIndex: Int, queue: List<Track>): Int {
        if (queue.isEmpty()) return -1
        val clampedPreferred = preferredIndex.coerceIn(0, queue.lastIndex)
        if (trackIdsMatch(queue[clampedPreferred].id, trackId)) {
            return clampedPreferred
        }
        var distance = 1
        while (clampedPreferred - distance >= 0 || clampedPreferred + distance <= queue.lastIndex) {
            val forward = clampedPreferred + distance
            if (forward <= queue.lastIndex && trackIdsMatch(queue[forward].id, trackId)) {
                return forward
            }
            val backward = clampedPreferred - distance
            if (backward >= 0 && trackIdsMatch(queue[backward].id, trackId)) {
                return backward
            }
            distance++
        }
        return -1
    }

    fun currentQueueIndex(state: PlaybackStatus): Int {
        val currentId = state.currentTrack?.id ?: return 0
        val idx = findQueueIndexNear(currentId, spotifyCurrentQueueIndex, state.queue)
        if (idx >= 0) {
            return idx
        }
        return spotifyCurrentQueueIndex.coerceIn(0, state.queue.lastIndex.coerceAtLeast(0))
    }

    suspend fun resolveSpotifyQueueIndex(activeQueue: List<Track>, currentTrackId: String?): Int {
        if (activeQueue.isEmpty()) return 0
        val snapshotTrackId = spotifyPlaybackController.snapshot()?.currentTrackId
        if (!snapshotTrackId.isNullOrBlank()) {
            val snapshotIndex = findQueueIndexNear(snapshotTrackId, spotifyCurrentQueueIndex, activeQueue)
            if (snapshotIndex >= 0) {
                return snapshotIndex
            }
        }
        if (!currentTrackId.isNullOrBlank()) {
            val currentIndex = findQueueIndexNear(currentTrackId, spotifyCurrentQueueIndex, activeQueue)
            if (currentIndex >= 0) {
                return currentIndex
            }
        }
        return spotifyCurrentQueueIndex.coerceIn(0, activeQueue.lastIndex)
    }
}
