package com.anyplayer.android.feature.playlists

import com.anyplayer.android.core.model.DeduplicateResult
import com.anyplayer.android.core.model.DeduplicateTracksResult
import com.anyplayer.android.core.model.DuplicateGroup
import com.anyplayer.android.core.model.DuplicateOccurrence
import com.anyplayer.android.core.model.PlaylistTrack
import com.anyplayer.android.core.model.Track

/**
 * Stateless dedup utility for custom and union playlists.
 *
 * Matches Rust and TypeScript dedup semantics exactly:
 * - Key: `title.trim().lowercase() + "|" + artist.trim().lowercase()`
 * - First occurrence is kept; subsequent occurrences are recorded as duplicates.
 * - `DuplicateGroup.occurrences` does NOT include the first (kept) occurrence.
 *
 * This object has no dependencies on repositories, coroutines, or DI and may be
 * called from any layer (playback, UI, data) without lifecycle concerns.
 */
object DistinctPlaylistUtils {

    /**
     * Deduplicate [tracks] by normalized title+artist key.
     *
     * @param tracks Ordered list of playlist tracks to deduplicate.
     * @return [DeduplicateResult] containing the deduplicated track list and any
     *         duplicate groups found. When no duplicates exist, [DeduplicateResult.duplicateGroups]
     *         is empty and [DeduplicateResult.tracks] equals the input.
     */
    fun deduplicate(tracks: List<PlaylistTrack>): DeduplicateResult {
        // Maps normalized key -> index of first occurrence in the original input.
        val firstOccurrenceIndex = mutableMapOf<String, Int>()
        val dedupedTracks = mutableListOf<PlaylistTrack>()
        // Maps normalized key -> duplicate occurrences (all after the first).
        val duplicateMap = mutableMapOf<String, MutableList<DuplicateOccurrence>>()

        tracks.forEachIndexed { index, track ->
            val key = "${track.title.trim().lowercase()}|${track.artist.trim().lowercase()}"
            if (!firstOccurrenceIndex.containsKey(key)) {
                firstOccurrenceIndex[key] = index
                dedupedTracks.add(track)
            } else {
                duplicateMap
                    .getOrPut(key) { mutableListOf() }
                    .add(DuplicateOccurrence(index = index, trackId = track.trackId))
            }
        }

        val duplicateGroups = firstOccurrenceIndex
            .filter { (key, _) -> duplicateMap.containsKey(key) }
            .map { (key, firstIdx) ->
                DuplicateGroup(
                    key = key,
                    firstOccurrenceIndex = firstIdx,
                    occurrences = duplicateMap.getValue(key)
                )
            }

        return DeduplicateResult(
            tracks = dedupedTracks,
            duplicateGroups = duplicateGroups
        )
    }

    /**
     * Deduplicate [tracks] (a [Track] list) by normalized title+artist key.
     *
     * Identical key contract to [deduplicate]: `title.trim().lowercase() + "|" + artist.trim().lowercase()`.
     * Used for union and provider queue inputs which produce [Track] objects rather than [PlaylistTrack].
     *
     * @param tracks Ordered list of tracks to deduplicate.
     * @return [DeduplicateTracksResult] containing the deduplicated track list and any
     *         duplicate groups found. When no duplicates exist, [DeduplicateTracksResult.duplicateGroups]
     *         is empty and [DeduplicateTracksResult.tracks] equals the input.
     */
    fun deduplicateTracks(tracks: List<Track>): DeduplicateTracksResult {
        // Maps normalized key -> index of first occurrence in the original input.
        val firstOccurrenceIndex = mutableMapOf<String, Int>()
        val dedupedTracks = mutableListOf<Track>()
        // Maps normalized key -> duplicate occurrences (all after the first).
        val duplicateMap = mutableMapOf<String, MutableList<DuplicateOccurrence>>()

        tracks.forEachIndexed { index, track ->
            val key = "${track.title.trim().lowercase()}|${track.artist.trim().lowercase()}"
            if (!firstOccurrenceIndex.containsKey(key)) {
                firstOccurrenceIndex[key] = index
                dedupedTracks.add(track)
            } else {
                duplicateMap
                    .getOrPut(key) { mutableListOf() }
                    .add(DuplicateOccurrence(index = index, trackId = track.id))
            }
        }

        val duplicateGroups = firstOccurrenceIndex
            .filter { (key, _) -> duplicateMap.containsKey(key) }
            .map { (key, firstIdx) ->
                DuplicateGroup(
                    key = key,
                    firstOccurrenceIndex = firstIdx,
                    occurrences = duplicateMap.getValue(key)
                )
            }

        return DeduplicateTracksResult(
            tracks = dedupedTracks,
            duplicateGroups = duplicateGroups
        )
    }
}
