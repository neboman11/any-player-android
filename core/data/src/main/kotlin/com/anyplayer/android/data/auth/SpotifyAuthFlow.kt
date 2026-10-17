package com.anyplayer.android.data.auth

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.data.providers.SpotifyClient
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

@Singleton
class SpotifyAuthFlow @Inject constructor(
    private val sessions: SpotifyAuthSessionStore,
    private val accounts: SpotifyAccounts,
    private val spotify: SpotifyClient,
    private val connections: ConnectionStore,
    @Named("spotifyClientId") private val clientId: String,
) {
    private val mutex = Mutex()
    suspend fun beginAuth(): String = withContext(Dispatchers.IO) { mutex.withLock {
        val id = clientId.trim()
        require(id.isNotBlank()) { "Spotify client ID is required" }
        val random = SecureRandom()
        fun randomString(length: Int): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(length).also(random::nextBytes)).take(length)
        val session = PendingSpotifyAuthSession(randomString(24), randomString(64), id, REDIRECT_URI)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(session.codeVerifier.toByteArray(StandardCharsets.US_ASCII)))
        val url = "https://accounts.spotify.com/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code").addQueryParameter("client_id", id)
            .addQueryParameter("redirect_uri", REDIRECT_URI).addQueryParameter("scope", SCOPES)
            .addQueryParameter("state", session.state).addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge).build().toString()
        sessions.save(session)
        url
    } }
    suspend fun completeAuth(callback: String): StoredConnection = withContext(Dispatchers.IO) { mutex.withLock {
        val pending = sessions.read() ?: error("No pending Spotify auth session. Start linking again.")
        val uri = try { URI(callback) } catch (_: Exception) { error("Invalid Spotify callback URI") }
        val params = try {
            uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate { part ->
                fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
                decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
            }
        } catch (_: Exception) { error("Invalid Spotify callback URI") }
        suspend fun fail(message: String): Nothing { sessions.clear(); error(message) }
        params["error"]?.let { fail("Spotify authorization failed: $it") }
        if (params["state"].isNullOrBlank() || params["state"] != pending.state)
            fail("Spotify auth state mismatch. Start linking again.")
        val code = params["code"]?.takeIf(String::isNotBlank)
            ?: fail("Spotify callback did not include an authorization code")
        val tokens = try { accounts.exchange(pending.clientId, code, pending.codeVerifier, pending.redirectUri) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            ?: fail("Unable to exchange Spotify authorization code")
        val check = try { spotify.validate(StoredConnection(SourceType.SPOTIFY, token = tokens.accessToken)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e.message ?: "Spotify token validation failed") }
        val connection = StoredConnection(SourceType.SPOTIFY, username = check.username, token = tokens.accessToken,
            refreshToken = tokens.refreshToken, tokenExpiresAt = tokens.expiresIn?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 1000 },
            spotifyPremium = check.premium, playbackReady = true)
        connections.update(SourceType.SPOTIFY) { connection }
        sessions.clear()
        connection
    } }
    companion object {
        const val REDIRECT_URI = "anyplayer://spotify-callback"
        const val SCOPES = "playlist-read-private playlist-read-collaborative user-read-private user-read-email user-library-read user-read-playback-state user-modify-playback-state user-read-currently-playing"
    }
}
