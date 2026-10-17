package com.anyplayer.android.startup

import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.data.auth.AuthRepository
import com.anyplayer.android.data.catalog.CatalogRepository
import com.anyplayer.android.data.catalog.ProviderRefreshFailure
import com.anyplayer.android.data.sync.SyncEngine
import com.anyplayer.android.data.sync.SyncUiState
import com.anyplayer.android.dj.DjController
import com.anyplayer.android.sync.FakeEngine
import com.anyplayer.android.sync.unused
import java.util.Optional
import javax.inject.Provider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAppStartupTest {
    @Test fun `restore and DJ activate before foreground then import refresh and sync in order`() = runTest {
        val calls = mutableListOf<String>()
        val restore = CompletableDeferred<Unit>()
        val foreground = CompletableDeferred<Unit>()
        val engine = FakeEngine().apply { onRestore = { calls += "engine"; restore.await() } }
        val auth = object : AuthRepository by unused<AuthRepository>() {
            override suspend fun restore(): List<ProviderConnectionProfile> {
                calls += "auth"
                return listOf(ProviderConnectionProfile(SourceType.JELLYFIN, true))
            }
        }
        val catalog = object : CatalogRepository by unused<CatalogRepository>() {
            override suspend fun refreshPlaylists(): List<ProviderRefreshFailure> { calls += "refresh"; return emptyList() }
        }
        val sync = object : SyncEngine by unused<SyncEngine>() {
            override val state = MutableStateFlow(SyncUiState())
            override fun start() { calls += "sync" }
        }
        val startup = DefaultAppStartup(Provider { engine }, Provider { auth }, Provider { catalog }, Provider { sync },
            Provider { calls += "dj"; unused<DjController>() }, Optional.of(LegacyCacheImport { calls += "import" }),
            object : StartupGate { override suspend fun awaitReady() { calls += "gate"; foreground.await() } }, backgroundScope)
        startup.start(); startup.start(); runCurrent()
        assertEquals(listOf("engine"), calls)
        restore.complete(Unit); runCurrent()
        assertEquals(listOf("engine", "dj", "auth", "gate"), calls)
        assertTrue(startup.status.value.inProgress)
        foreground.complete(Unit); runCurrent()
        assertEquals(listOf("engine", "dj", "auth", "gate", "import", "refresh", "sync"), calls)
        assertFalse(startup.status.value.inProgress)
        assertTrue(startup.status.value.warnings.isEmpty())
        assertEquals("Startup complete. 1 provider session(s) restored.", startup.status.value.message)
    }

    @Test fun `restore failures and per-provider refresh failures warn without preventing sync and retry clears warnings`() = runTest {
        var fail = true
        var restores = 0
        var syncStarts = 0
        val auth = object : AuthRepository by unused<AuthRepository>() {
            override suspend fun restore(): List<ProviderConnectionProfile> {
                restores++
                if (fail) error("expired session")
                return emptyList()
            }
        }
        val catalog = object : CatalogRepository by unused<CatalogRepository>() {
            override suspend fun refreshPlaylists() = if (fail) listOf(ProviderRefreshFailure(SourceType.PLEX, "offline")) else emptyList()
        }
        val sync = object : SyncEngine by unused<SyncEngine>() { override fun start() { syncStarts++ } }
        val startup = DefaultAppStartup(Provider { FakeEngine() }, Provider { auth }, Provider { catalog }, Provider { sync },
            Provider { unused<DjController>() }, Optional.empty(), object : StartupGate { override suspend fun awaitReady() = Unit }, backgroundScope)
        startup.start(); runCurrent()
        assertEquals(listOf("Session restore failed: expired session", "Playlist refresh failed: PLEX: offline"), startup.status.value.warnings)
        assertEquals(1, syncStarts)
        fail = false; startup.retry(); runCurrent()
        assertEquals(2, restores)
        assertEquals(2, syncStarts)
        assertTrue(startup.status.value.warnings.isEmpty())
        assertFalse(startup.status.value.inProgress)
    }

    @Test fun `thrown playlist refresh error preserves local startup and sync`() = runTest {
        var started = false
        val auth = object : AuthRepository by unused<AuthRepository>() { override suspend fun restore() = emptyList<ProviderConnectionProfile>() }
        val catalog = object : CatalogRepository by unused<CatalogRepository>() {
            override suspend fun refreshPlaylists(): List<ProviderRefreshFailure> = error("offline")
        }
        val sync = object : SyncEngine by unused<SyncEngine>() { override fun start() { started = true } }
        val startup = DefaultAppStartup(Provider { FakeEngine() }, Provider { auth }, Provider { catalog }, Provider { sync },
            Provider { unused<DjController>() }, Optional.empty(), object : StartupGate { override suspend fun awaitReady() = Unit }, backgroundScope)
        startup.start(); runCurrent()
        assertEquals(listOf("Playlist refresh failed: offline"), startup.status.value.warnings)
        assertTrue(started)
    }
}
