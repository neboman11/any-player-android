package com.anyplayer.android.data.auth

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.data.net.HttpFailure
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class DefaultSpotifyTokenProvider(
    private val store: ConnectionStore,
    private val accounts: SpotifyAccounts,
    private val clientId: String,
    private val now: () -> Long,
) : SpotifyTokenProvider {
    @Inject constructor(store: ConnectionStore, accounts: SpotifyAccounts, @Named("spotifyClientId") clientId: String) :
        this(store, accounts, clientId, System::currentTimeMillis)
    private val mutex = Mutex()
    @Volatile private var refreshGeneration = 0L
    private var lastRefreshSucceeded = false
    override suspend fun accessToken(): String? = refresh(false)
    override suspend fun refreshAfterUnauthorized(): String? = refresh(true)

    private suspend fun refresh(force: Boolean): String? {
        val observed = store.awaitLoaded()[SourceType.SPOTIFY]?.token
        val observedGeneration = refreshGeneration
        return mutex.withLock {
            val connection = store.awaitLoaded()[SourceType.SPOTIFY] ?: return@withLock null
            val token = connection.token?.takeIf(String::isNotBlank) ?: return@withLock null
            val expiry = connection.tokenExpiresAt
            if (token != observed) return@withLock if (expiry == null || now() < expiry) token else null
            if (observedGeneration != refreshGeneration)
                return@withLock if ((!force || lastRefreshSucceeded) && (expiry == null || now() < expiry)) token else null
            if (!force && (expiry == null || now() < expiry - 300_000)) return@withLock token
            if (connection.refreshToken.isNullOrBlank()) {
                if (force) store.update(SourceType.SPOTIFY) { current ->
                    if (current?.token == token) current.copy(playbackReady = false) else current
                }
                return@withLock if (!force && (expiry == null || now() < expiry)) token else null
            }
            val result = try {
                accounts.refresh(clientId, connection.refreshToken)
            } catch (e: CancellationException) { throw e
            } catch (e: HttpFailure) {
                if (e.status == 400 || e.status == 401) store.update(SourceType.SPOTIFY) { current ->
                    current?.takeIf { it.token == token }?.copy(playbackReady = false) ?: current
                }
                null
            } catch (_: Exception) { null }
            refreshGeneration++
            lastRefreshSucceeded = result != null
            if (result == null) return@withLock if (!force && (expiry == null || now() < expiry)) token else null
            val ready = try { accounts.playbackReady(result.accessToken) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { connection.playbackReady }
            store.update(SourceType.SPOTIFY) { current ->
                if (current?.token != token) current else current.copy(token = result.accessToken,
                    refreshToken = result.refreshToken ?: current.refreshToken,
                    tokenExpiresAt = result.expiresIn?.takeIf { it > 0 }?.let { now() + it * 1000 },
                    playbackReady = ready)
            }
            store.awaitLoaded()[SourceType.SPOTIFY]?.token
        }
    }
}
