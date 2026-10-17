package com.anyplayer.android.data

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.data.auth.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FakeConnections(vararg connections: StoredConnection) : ConnectionStore {
    override val connections = MutableStateFlow(connections.associateBy { it.source })
    private val mutex = Mutex()
    override suspend fun awaitLoaded() = connections.value
    override suspend fun update(source: SourceType, transform: (StoredConnection?) -> StoredConnection?) = mutex.withLock {
        val next = transform(connections.value[source])
        connections.value = if (next == null) connections.value - source else connections.value + (source to next)
    }
}
class FakeTokens(var token: String? = "token", private val refreshed: String = "new") : SpotifyTokenProvider {
    var refreshCount = 0
    override suspend fun accessToken() = token
    override suspend fun refreshAfterUnauthorized(): String { refreshCount++; token = refreshed; return refreshed }
}
class FakeSessions(var pending: PendingSpotifyAuthSession? = null) : SpotifyAuthSessionStore {
    var clears = 0
    override suspend fun read() = pending
    override suspend fun save(session: PendingSpotifyAuthSession) { pending = session }
    override suspend fun clear() { clears++; pending = null }
}
