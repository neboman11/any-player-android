package com.anyplayer.android.data.auth

import com.anyplayer.android.data.store.SecurePrefs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PendingSpotifyAuthSession(val state: String, val codeVerifier: String, val clientId: String, val redirectUri: String)

interface SpotifyAuthSessionStore {
    suspend fun read(): PendingSpotifyAuthSession?
    suspend fun save(session: PendingSpotifyAuthSession)
    suspend fun clear()
}

@Singleton
class SecureSpotifyAuthSessionStore @Inject constructor(private val secure: SecurePrefs) : SpotifyAuthSessionStore {
    private val mutex = Mutex()
    private var loaded = false
    private var session: PendingSpotifyAuthSession? = null
    override suspend fun read(): PendingSpotifyAuthSession? = withContext(Dispatchers.IO) { mutex.withLock {
        if (!loaded) {
            val prefs = secure.open(SecurePrefs.SPOTIFY_AUTH_SESSION)
            val values = listOf("state", "codeVerifier", "clientId", "redirectUri").map { prefs.getString(it, null) }
            session = if (values.all { it != null }) PendingSpotifyAuthSession(values[0]!!, values[1]!!, values[2]!!, values[3]!!) else null
            loaded = true
        }
        session
    } }
    override suspend fun save(session: PendingSpotifyAuthSession) = withContext(Dispatchers.IO) { mutex.withLock {
        check(secure.open(SecurePrefs.SPOTIFY_AUTH_SESSION).edit().putString("state", session.state)
            .putString("codeVerifier", session.codeVerifier).putString("clientId", session.clientId)
            .putString("redirectUri", session.redirectUri).commit())
        this@SecureSpotifyAuthSessionStore.session = session
        loaded = true
    } }
    override suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock {
        check(secure.open(SecurePrefs.SPOTIFY_AUTH_SESSION).edit().clear().commit())
        session = null
        loaded = true
    } }
}
