package com.anyplayer.android.data

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.StoredConnection
import com.anyplayer.android.data.providers.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProviderClientsTest {
    private val http = OkHttpClient()
    @Test fun jellyfinResolvesUserOnceAndPagesRawItems() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"Id":"first","Name":"First"},{"Id":"chosen","Name":"Chosen"}]"""))
            server.enqueue(MockResponse().setBody("""{"TotalRecordCount":3,"Items":[{"Id":"a","Type":"Audio","Name":"Song","Artists":["Artist"],"Album":"Album","RunTimeTicks":25000000,"AlbumId":"al","AlbumPrimaryImageTag":"tag","MediaSources":[{"MediaStreams":[{"Type":"Audio","BitRate":320000,"SampleRate":48000}]}]},{"Id":"folder","Type":"Folder"}]}"""))
            server.enqueue(MockResponse().setBody("""{"TotalRecordCount":3,"Items":[{"Id":"b","Type":"Audio","Name":"Other"}]}"""))
            server.enqueue(MockResponse().setBody("""{"TotalRecordCount":1,"Items":[{"Id":"p","Name":"Mix","ChildCount":2}]}"""))
            val conn = StoredConnection(SourceType.JELLYFIN, server.url("/").toString(), token = "secret", playlistPageSize = 2, jellyfinUserId = "chosen")
            val store = FakeConnections(conn)
            val client = JellyfinClient(http, store)
            val tracks = client.playlistTracks(conn, "p")
            assertEquals(listOf("a", "b"), tracks.map { it.id })
            assertEquals(2500L, tracks[0].durationMs)
            assertEquals(320, tracks[0].bitrateKbps)
            assertEquals(48000, tracks[0].sampleRateHz)
            assertTrue(tracks[0].imageUrl!!.contains("/Items/al/Images/Primary?tag=tag"))
            assertEquals("chosen", tracks[0].url!!.toHttpUrl().queryParameter("UserId"))
            assertEquals("chosen", store.connections.value[SourceType.JELLYFIN]?.jellyfinUserId)
            assertEquals("Mix", client.playlists(conn).single().name)
            assertEquals("/Users", server.takeRequest().path)
            val first = server.takeRequest()
            assertEquals("secret", first.getHeader("X-Emby-Token"))
            assertTrue(first.getHeader("X-Emby-Authorization")!!.contains("Client=\"AnyPlayer\""))
            assertEquals("0", first.requestUrl!!.queryParameter("StartIndex"))
            assertEquals("2", server.takeRequest().requestUrl!!.queryParameter("StartIndex"))
            assertTrue(server.takeRequest().path!!.contains("IncludeItemTypes=Playlist"))
        }
    }
    @Test fun jellyfinStreamAndArtworkUrlsMatchLegacyBytesAndSearchFetchesOnePage() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"Id":"uid","Name":"Listener"}]"""))
            server.enqueue(MockResponse().setBody("""{"TotalRecordCount":5000,"Items":[{"Id":"a","Type":"Audio","Name":"Song","AlbumId":"al","AlbumPrimaryImageTag":"tag"},{"Id":"b","Type":"Audio","Name":"Two"}]}"""))
            val base = server.url("/jf/").toString()
            // Page size 1 would need thousands of requests if search walked every page.
            val conn = StoredConnection(SourceType.JELLYFIN, base, token = "secret", playlistPageSize = 1, jellyfinUserId = "uid")
            val result = JellyfinClient(http, FakeConnections(conn)).search(conn, "song", SearchType.TRACKS, 1)
            assertEquals(listOf("a"), result.tracks.map { it.id })
            val root = base.trimEnd('/')
            assertEquals("$root/Audio/a/universal?UserId=uid&Container=opus,mp3,aac,m4a,flac,webma,webm,wav,ogg&AudioCodec=aac,mp3,vorbis,opus", result.tracks[0].url)
            assertEquals("$root/Items/al/Images/Primary?tag=tag", result.tracks[0].imageUrl)
            assertEquals("/jf/Users", server.takeRequest().path)
            val search = server.takeRequest().requestUrl!!
            assertEquals("1", search.queryParameter("Limit"))
            assertEquals("0", search.queryParameter("StartIndex"))
            assertEquals("song", search.queryParameter("searchTerm"))
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun plexValidateUsesLegacyErrorTexts() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            try { PlexClient(http).validate(StoredConnection(SourceType.PLEX, server.url("/").toString(), token = "t")); fail("Expected auth failure") }
            catch (e: com.anyplayer.android.data.net.HttpFailure) { assertEquals("Plex authentication failed: HTTP 401", e.message) }
        }
        val closed = MockWebServer().apply { start() }
        val url = closed.url("/").toString()
        closed.shutdown()
        try { PlexClient(http).validate(StoredConnection(SourceType.PLEX, url, token = "t")); fail("Expected connection failure") }
        catch (e: java.io.IOException) { assertTrue(e.message, e.message!!.startsWith("Plex connection test failed: ")) }
    }
    @Test fun plexPagesAndSignsStreamAndArtworkWithoutProbe() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"MediaContainer":{"totalSize":2,"Metadata":[{"ratingKey":"1","title":"Song","grandparentTitle":"Artist","parentTitle":"Album","duration":1234,"thumb":"/art/1","Media":[{"bitrate":192,"Part":[{"key":"/library/parts/1/file.mp3"}]}]}]}}"""))
            server.enqueue(MockResponse().setBody("""{"MediaContainer":{"totalSize":2,"Metadata":[{"ratingKey":"2","title":"Second"}]}}"""))
            val conn = StoredConnection(SourceType.PLEX, server.url("/").toString(), token = "a b", playlistPageSize = 1)
            val tracks = PlexClient(http).playlistTracks(conn, "mix")
            assertEquals(listOf("1", "2"), tracks.map { it.id })
            assertEquals("Artist", tracks[0].artist)
            assertEquals(1234L, tracks[0].durationMs)
            assertTrue(tracks[0].url!!.contains("/library/parts/1/file.mp3?X-Plex-Token=a%20b"))
            assertTrue(tracks[0].imageUrl!!.contains("/art/1?X-Plex-Token=a%20b"))
            val first = server.takeRequest()
            assertEquals("application/json", first.getHeader("Accept"))
            assertEquals("0", first.requestUrl!!.queryParameter("containerStart"))
            assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("containerStart"))
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun spotifyAdvancesRequestedPageDespiteFilteredNullTracksAndRefreshes401() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"total":3,"items":[{"track":null},{"track":{"id":"1","name":"Song","artists":[{"name":"A"},{"name":"B"}],"album":{"name":"Album","images":[{"width":640,"url":"large"},{"width":300,"url":"best"}]},"duration_ms":123}}]}"""))
            server.enqueue(MockResponse().setBody("""{"total":3,"items":[{"track":{"id":"2","name":"Second"}}]}"""))
            val tokens = FakeTokens()
            val client = SpotifyClient(http, tokens, server.url("/v1/").toString())
            val tracks = client.playlistTracks(StoredConnection(SourceType.SPOTIFY, playlistPageSize = 2), "mix")
            assertEquals(listOf("1", "2"), tracks.map { it.id })
            assertEquals("A, B", tracks[0].artist)
            assertEquals("best", tracks[0].imageUrl)
            assertEquals("spotify:track:1", tracks[0].url)
            assertEquals(1, tokens.refreshCount)
            assertEquals("Bearer token", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
            val last = server.takeRequest()
            assertEquals("2", last.requestUrl!!.queryParameter("offset"))
            assertEquals("from_token", last.requestUrl!!.queryParameter("market"))
        }
    }
    @Test fun spotifyPlaylistListFetchesBeyondFiftyAndSearchOnlyRequestsType() = runTest {
        MockWebServer().use { server ->
            val items = (1..50).joinToString(",") { """{"id":"$it","name":"P$it","owner":{"display_name":"Owner"},"tracks":{"total":1}}""" }
            server.enqueue(MockResponse().setBody("""{"total":51,"items":[$items]}"""))
            server.enqueue(MockResponse().setBody("""{"total":51,"items":[{"id":"51","name":"Last"}]}"""))
            server.enqueue(MockResponse().setBody("""{"playlists":{"items":[{"id":"found","name":"Found"}]}}"""))
            val client = SpotifyClient(http, FakeTokens(), server.url("/v1/").toString())
            val conn = StoredConnection(SourceType.SPOTIFY)
            assertEquals(51, client.playlists(conn).size)
            val result = client.search(conn, "find", SearchType.PLAYLISTS, 100)
            assertTrue(result.tracks.isEmpty())
            assertEquals("found", result.playlists.single().id)
            server.takeRequest()
            assertEquals("50", server.takeRequest().requestUrl!!.queryParameter("offset"))
            val request = server.takeRequest()
            assertEquals("playlist", request.requestUrl!!.queryParameter("type"))
            assertEquals("50", request.requestUrl!!.queryParameter("limit"))
        }
    }
}
