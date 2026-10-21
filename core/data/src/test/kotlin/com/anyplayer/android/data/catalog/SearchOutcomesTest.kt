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

    @Test fun providerPrefixedFailuresDoNotRepeatProviderNames() {
        val messages = listOf(
            SourceType.JELLYFIN to "Jellyfin: HTTP 503",
            SourceType.PLEX to "Plex is offline",
            SourceType.SPOTIFY to "Spotify token validation failed",
        )
        val failures = messages.map { (provider, message) ->
            providerSearchFailure(provider, IOException(message))
        }
        val error = assertThrows(IllegalStateException::class.java) {
            mergeSearchOutcomes(failures.map { Result.failure(it) })
        }
        assertEquals("Jellyfin: HTTP 503; Plex is offline; Spotify token validation failed", error.message)
        failures.zip(messages).forEach { (failure, original) ->
            assertEquals(original.second, failure.message)
            assertEquals(original.second, failure.cause?.message)
        }
    }

    @Test fun providerPrefixIsRecognizedRegardlessOfCase() {
        listOf(
            SourceType.JELLYFIN to "jellyfin is offline",
            SourceType.PLEX to "PLEX: HTTP 503",
            SourceType.SPOTIFY to "sPoTiFy token validation failed",
        ).forEach { (provider, message) ->
            val cause = IOException(message)
            val failure = providerSearchFailure(provider, cause)
            assertEquals(message, failure.message)
            assertSame(cause, failure.cause)
        }
    }

    @Test fun failureWithoutProviderPrefixStillGetsItsOwnProviderLabel() {
        assertEquals("Jellyfin: HTTP 503", providerSearchFailure(SourceType.JELLYFIN, IOException("HTTP 503")).message)
        assertEquals("Plex: offline", providerSearchFailure(SourceType.PLEX, IOException("offline")).message)
        assertEquals("Spotify: Plex is offline", providerSearchFailure(SourceType.SPOTIFY, IOException("Plex is offline")).message)
        assertEquals("Spotify: Cannot contact Spotify", providerSearchFailure(SourceType.SPOTIFY, IOException("Cannot contact Spotify")).message)
    }

    @Test fun providerFailureWithoutUsableMessageKeepsLabelAndNeutralFallback() {
        listOf(null, "", "   ").forEach { message ->
            val cause = IOException(message)
            val failure = providerSearchFailure(SourceType.SPOTIFY, cause)
            assertEquals("Spotify: Unknown error", failure.message)
            assertSame(cause, failure.cause)
        }
    }

    @Test fun everySearchedProviderFailingIncludesEveryProviderInOrder() {
        val jellyfinFailure = IllegalStateException("Jellyfin: HTTP 503", HttpFailure(503, "HTTP 503"))
        val plexFailure = IllegalStateException("Plex: offline", IOException("offline"))
        val error = assertThrows(IllegalStateException::class.java) {
            mergeSearchOutcomes(listOf(Result.failure(jellyfinFailure), Result.failure(plexFailure)))
        }
        assertEquals("Jellyfin: HTTP 503; Plex: offline", error.message)
        assertSame(jellyfinFailure, error.cause)
    }

    @Test fun failureWithoutAMessageUsesNeutralFallback() {
        val error = assertThrows(IllegalStateException::class.java) {
            mergeSearchOutcomes(listOf(Result.failure(IOException())))
        }
        assertEquals("Unknown error", error.message)
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
