package com.anyplayer.android.data.store

import com.anyplayer.android.core.model.AppJson
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.data.auth.ConnectionStore
import com.anyplayer.android.data.auth.StoredConnection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

@Singleton
class SecureConnectionStore @Inject constructor(private val prefs: SecurePrefs) : ConnectionStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<SourceType, StoredConnection>>(emptyMap())
    override val connections = state.asStateFlow()
    private val loaded = scope.async {
        val file = prefs.open(SecurePrefs.PROVIDER_CONNECTIONS)
        state.value = listOf(SourceType.SPOTIFY, SourceType.JELLYFIN, SourceType.PLEX).mapNotNull { source ->
            file.getString(source.name, null)?.let { json ->
                runCatching { AppJson.decodeFromString<StoredConnection>(json) }.getOrNull()
                    ?.copy(source = source)?.let { source to it }
            }
        }.toMap()
    }
    override suspend fun awaitLoaded(): Map<SourceType, StoredConnection> {
        loaded.await()
        return state.value
    }
    override suspend fun update(source: SourceType, transform: (StoredConnection?) -> StoredConnection?) {
        loaded.await()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val value = transform(state.value[source])
                val editor = prefs.open(SecurePrefs.PROVIDER_CONNECTIONS).edit()
                if (value == null) editor.remove(source.name) else editor.putString(source.name, AppJson.encodeToString(value))
                check(editor.commit()) { "Unable to save provider connection" }
                state.value = if (value == null) state.value - source else state.value + (source to value)
            }
        }
    }
}
