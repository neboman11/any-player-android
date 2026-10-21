package com.anyplayer.android.data.catalog

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.SearchResult
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.net.HttpFailure
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SearchOutcomesTest {
    private val jellyfinTrack = Track("jf", "Jellyfin Finale", "Artist", source = SourceType.JELLYFIN)
    private val plexPlaylist = Playlist("px", "Plex Mix", "owner", 2, SourceType.PLEX)

    @Test fun everySearchedProviderFailingThrowsTheFirstFailure() {
        val error = assertThrows(HttpFailure::class.java) {
            mergeSearchOutcomes(listOf(Result.failure(HttpFailure(503, "HTTP 503")), Result.failure(IOException("offline"))))
        }
        assertEquals("HTTP 503", error.message)
    }

    @Test fun partialFailureKeepsResultsFromProvidersThatSucceeded() {
        val merged = mergeSearchOutcomes(listOf(
            Result.failure(HttpFailure(503, "HTTP 503")),
            Result.success(SearchResult(listOf(jellyfinTrack), listOf(plexPlaylist))),
        ))
        assertEquals(SearchResult(listOf(jellyfinTrack), listOf(plexPlaylist)), merged)
    }

    @Test fun emptySuccessAlongsideFailureIsAnEmptyResultNotAnError() {
        val merged = mergeSearchOutcomes(listOf(Result.success(SearchResult()), Result.failure(IOException("offline"))))
        assertEquals(SearchResult(), merged)
    }

    @Test fun resultsAreConcatenatedInProviderOrderAndNoProvidersIsEmpty() {
        val other = jellyfinTrack.copy(id = "px", source = SourceType.PLEX)
        val merged = mergeSearchOutcomes(listOf(Result.success(SearchResult(listOf(jellyfinTrack))), Result.success(SearchResult(listOf(other)))))
        assertEquals(listOf("jf", "px"), merged.tracks.map { it.id })
        assertEquals(SearchResult(), mergeSearchOutcomes(emptyList()))
    }
}
