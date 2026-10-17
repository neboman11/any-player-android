package com.anyplayer.android.sync

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.*
import com.anyplayer.android.data.catalog.*
import com.anyplayer.android.data.transfer.*
import com.anyplayer.android.ui.settings.FakeConnections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class SyncImportsTest {
    private class Fixture {
        val config = FakeConfig()
        val connections = FakeConnections()
        val revalidated = mutableListOf<SourceType>()
        var refreshes = 0
        val auth = object : AuthRepository by unused<AuthRepository>() {
            override suspend fun connectPlex(serverUrl: String, token: String, pageSize: Int): ProviderConnectionProfile {
                assertEquals("https://new", serverUrl)
                assertEquals("secret", token)
                assertEquals(450, pageSize)
                revalidated += SourceType.PLEX
                return ProviderConnectionProfile(SourceType.PLEX, true)
            }
        }
        val catalog = object : CatalogRepository by unused<CatalogRepository>() {
            override suspend fun refreshPlaylists(): List<ProviderRefreshFailure> { refreshes++; return emptyList() }
        }
        val status = SyncImportStatus()
        val imports = SyncImports(config, connections, auth, catalog, status)
    }
    @Test fun `import summary reaches status and only changed URL revalidates provider`() = runTest {
        val f = Fixture()
        val old = StoredConnection(SourceType.PLEX, serverUrl = "https://old", token = "secret", playlistPageSize = 450)
        f.connections.update(SourceType.PLEX) { old }
        f.config.summary = ImportSummary(playlistsAdded = 2, warnings = listOf("legacy warning"))
        f.config.onImport = { f.connections.update(SourceType.PLEX) { old.copy(serverUrl = "https://new") } }
        assertTrue(f.imports.apply(JsonObject(emptyMap()), MergePolicy.MERGE_KEEP_LOCAL))
        assertEquals(listOf(SourceType.PLEX), f.revalidated)
        assertEquals(1, f.refreshes)
        assertEquals(f.config.summary.format("Sync import"), f.status.message.value)
        f.config.onImport = {}
        assertTrue(f.imports.apply(JsonObject(emptyMap()), MergePolicy.MERGE_KEEP_LOCAL))
        assertEquals(listOf(SourceType.PLEX), f.revalidated)
        assertEquals(1, f.refreshes)
    }
    @Test fun `import failure is described as import failure not server availability`() = runTest {
        val f = Fixture()
        f.config.onImport = { error("invalid config") }
        assertFalse(f.imports.apply(JsonObject(emptyMap()), MergePolicy.REPLACE_ALL))
        assertEquals("Sync import failed: invalid config", f.status.message.value)
        assertEquals(0, f.refreshes)
    }
    @Test fun `import cancellation propagates without publishing misleading failure`() = runTest {
        val f = Fixture()
        f.config.onImport = { throw CancellationException("cancelled") }
        try { f.imports.apply(JsonObject(emptyMap()), MergePolicy.REPLACE_ALL); fail("Cancellation swallowed") }
        catch (_: CancellationException) { assertNull(f.status.message.value) }
    }
}
