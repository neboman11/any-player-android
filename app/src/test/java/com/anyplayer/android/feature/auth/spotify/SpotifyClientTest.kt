package com.anyplayer.android.feature.auth.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyClientTest {
    @Test
    fun createPkceSession_containsExpectedParameters() {
        val okHttpClient = OkHttpClient()
        val json = Json
        val client = SpotifyAuthClient(
            okHttpClient = okHttpClient,
            json = json,
            spotifyApiExecutor = SpotifyApiExecutor(okHttpClient = okHttpClient, json = json)
        )
        val session = client.createPkceSession(
            clientId = "client-123",
            redirectUri = "anyplayer://spotify-callback"
        )
        val url = session.authorizationUrl

        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client-123"))
        assertTrue(url.contains("redirect_uri=anyplayer%3A%2F%2Fspotify-callback"))
    }

    @Test
    fun joinArtistNames_singleArtist_returnsSingleName() {
        val artists = buildJsonArray {
            add(buildJsonObject { put("name", "Artist One") })
        }
        assertEquals("Artist One", joinArtistNames(artists))
    }

    @Test
    fun joinArtistNames_multipleArtists_joinsWithComma() {
        val artists = buildJsonArray {
            add(buildJsonObject { put("name", "Artist One") })
            add(buildJsonObject { put("name", "Artist Two") })
            add(buildJsonObject { put("name", "Artist Three") })
        }
        assertEquals("Artist One, Artist Two, Artist Three", joinArtistNames(artists))
    }

    @Test
    fun joinArtistNames_nullArray_returnsUnknownArtist() {
        assertEquals("Unknown Artist", joinArtistNames(null))
    }

    @Test
    fun joinArtistNames_emptyArray_returnsUnknownArtist() {
        assertEquals("Unknown Artist", joinArtistNames(JsonArray(emptyList())))
    }

    @Test
    fun joinArtistNames_blankArtistNames_returnsUnknownArtist() {
        val artists = buildJsonArray {
            add(buildJsonObject { put("name", "   ") })
            add(buildJsonObject { put("name", "") })
        }
        assertEquals("Unknown Artist", joinArtistNames(artists))
    }

    @Test
    fun joinArtistNames_mixedBlankAndValidNames_skipsBlank() {
        val artists = buildJsonArray {
            add(buildJsonObject { put("name", "Valid Artist") })
            add(buildJsonObject { put("name", "   ") })
        }
        assertEquals("Valid Artist", joinArtistNames(artists))
    }
}
