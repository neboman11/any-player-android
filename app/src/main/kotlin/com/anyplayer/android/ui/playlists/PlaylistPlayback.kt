package com.anyplayer.android.ui.playlists

import com.anyplayer.android.core.model.Dedupe
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.catalog.CatalogRepository
import com.anyplayer.android.data.playlists.PlaybackSelection
import com.anyplayer.android.data.playlists.PlaylistRepository
import com.anyplayer.android.playback.PlaybackEngine
import com.anyplayer.android.ui.components.PlaylistChoice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Provider playlist "Play": distinct (title|artist, first kept) applied when enabled. */
object ProviderPlayback {
    /**
     * [fromIndex] is the original-list index of the tapped track; with distinct on, playback
     * starts at the kept occurrence of that track's dedupe key.
     */
    fun selection(tracks: List<Track>, distinct: Boolean, fromIndex: Int? = null): PlaybackSelection {
        if (!distinct) {
            return PlaybackSelection(tracks, (fromIndex ?: 0).coerceIn(0, (tracks.size - 1).coerceAtLeast(0)))
        }
        val kept = Dedupe.deduplicateTracks(tracks).tracks
        val start = fromIndex?.let { index ->
            val track = tracks.getOrNull(index) ?: return@let 0
            val key = Dedupe.key(track.title, track.artist)
            kept.indexOfFirst { Dedupe.key(it.title, it.artist) == key }.coerceAtLeast(0)
        } ?: 0
        return PlaybackSelection(kept, start)
    }
}

/** Loads a provider playlist with its distinct preference and starts it. False when empty. */
internal suspend fun playProviderPlaylist(
    catalog: CatalogRepository,
    engine: PlaybackEngine,
    computeDispatcher: CoroutineDispatcher,
    source: SourceType,
    playlistId: String,
): Boolean {
    val tracks = catalog.tracks(source, playlistId)
    val distinct = catalog.isDistinct(source, playlistId)
    val selection = withContext(computeDispatcher) { ProviderPlayback.selection(tracks, distinct) }
    return engine.startSelection(selection)
}

/** Starts a custom playlist through the repository's playback selection. False when empty. */
internal suspend fun playCustomPlaylist(
    playlists: PlaylistRepository,
    engine: PlaybackEngine,
    playlistId: String,
    fromIndex: Int? = null,
): Boolean = engine.startSelection(playlists.playbackSelection(playlistId, fromIndex))

internal fun PlaybackEngine.startSelection(selection: PlaybackSelection): Boolean {
    if (selection.tracks.isEmpty()) return false
    setQueue(selection.tracks, selection.startIndex, autoPlay = true)
    return true
}

/** STANDARD custom playlists offered by the "Add to Playlist" picker. */
internal fun PlaylistRepository.standardPlaylistChoices(): Flow<List<PlaylistChoice>> =
    customPlaylists.map { list ->
        list.filter { it.playlistType == PlaylistType.STANDARD }
            .map { PlaylistChoice(it.id, it.name, it.trackCount) }
    }

internal const val NOTHING_TO_PLAY = "No tracks to play"
