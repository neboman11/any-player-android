package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import kotlin.random.Random

/**
 * Stateless queue-ordering helpers for [PlaybackQueueManager]. No dependencies on
 * repositories, coroutines, or DI.
 */
object QueueOrderingUtils {

    fun buildOrderedQueue(
        queue: List<Track>,
        currentTrackId: String?,
        shuffleEnabled: Boolean
    ): List<Track> {
        if (!shuffleEnabled || queue.size <= 1) return queue
        val currentIndex = currentTrackId?.let { id -> queue.indexOfFirst { it.id == id } } ?: -1
        val current = currentIndex.takeIf { it >= 0 }?.let { queue[it] }
        val remainder = if (current != null) {
            queue.filterIndexed { index, _ -> index != currentIndex }
        } else {
            queue
        }
        val shuffledRemainder = remainder.shuffled()
        return if (current != null) listOf(current) + shuffledRemainder else shuffledRemainder
    }

    fun resolveInitialStartIndex(
        tracks: List<Track>,
        requestedStartIndex: Int,
        autoPlay: Boolean,
        shuffleEnabled: Boolean,
        spotifyMode: Boolean
    ): Int {
        val normalizedStartIndex = requestedStartIndex.coerceIn(0, tracks.lastIndex)
        if (!autoPlay || !shuffleEnabled || requestedStartIndex != 0 || tracks.size <= 1) {
            return normalizedStartIndex
        }

        if (spotifyMode) {
            return Random.nextInt(tracks.size)
        }

        val playableIndices = tracks.mapIndexedNotNull { queueIndex, track ->
            queueIndex.takeIf { !track.url.isNullOrBlank() && track.source != SourceType.SPOTIFY }
        }
        return playableIndices.randomOrNull() ?: normalizedStartIndex
    }
}
