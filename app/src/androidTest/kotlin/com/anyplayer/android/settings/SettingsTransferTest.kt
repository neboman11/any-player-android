package com.anyplayer.android.settings

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anyplayer.android.MainActivity
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.ConnectionStore
import com.anyplayer.android.data.auth.StoredConnection
import com.anyplayer.android.data.playlists.PlaylistRepository
import com.anyplayer.android.data.settings.PlayerSettingsStore
import com.anyplayer.android.data.settings.SyncSettingsStore
import com.anyplayer.android.data.sync.SyncEngine
import com.anyplayer.android.data.transfer.*
import com.anyplayer.android.playback.PlaybackEngine
import com.anyplayer.android.sync.FakeSyncServer
import com.anyplayer.android.sync.SyncImportStatus
import com.anyplayer.android.testing.Device
import com.anyplayer.android.testing.Device.awaitUntil
import com.anyplayer.android.testing.Device.onMain
import com.anyplayer.android.ui.settings.AndroidSettingsDocuments
import com.anyplayer.android.ui.settings.GeneralSettingsTab
import com.anyplayer.android.ui.settings.GeneralSettingsViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Bypasses only SAF selection: ViewModel receives file URIs; the real document adapter opens streams.
 * GeneralSettingsTab renders the result. No global Hilt replacement affects other suites. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsTransferTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val notifications = Device.notificationPermission()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    @Inject lateinit var engine: Provider<PlaybackEngine>
    @Inject lateinit var sync: Provider<SyncEngine>
    @Inject lateinit var syncSettings: SyncSettingsStore
    @Inject lateinit var playerSettings: PlayerSettingsStore
    @Inject lateinit var playlists: PlaylistRepository
    @Inject lateinit var connections: ConnectionStore
    @Inject lateinit var transfer: StateTransfer
    @Inject lateinit var config: ConfigFiles
    @Inject lateinit var syncImports: SyncImportStatus
    private lateinit var vm: GeneralSettingsViewModel
    private lateinit var server: FakeSyncServer
    private lateinit var stateFile: File
    private val store = ViewModelStore()
    private var activity: ActivityScenario<MainActivity>? = null

    @Before fun setUp(): Unit = runBlocking {
        hilt.inject()
        server = FakeSyncServer()
        stateFile = File.createTempFile("settings-transfer-", ".json", Device.context.cacheDir)
        engine.get().awaitRestored()
        playerSettings.awaitLoaded()
        vm = onMain {
            GeneralSettingsViewModel(syncSettings, playerSettings, sync.get(), engine.get(), transfer,
                config, AndroidSettingsDocuments(Device.context), syncImports).also { store.put("transfer", it) }
        }
        activity = ActivityScenario.launch(MainActivity::class.java)
        activity!!.onActivity { host ->
            host.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { GeneralSettingsTab(vm) } } }
        }
    }

    @After fun tearDown() {
        activity?.close()
        onMain { store.clear(); engine.get().pause() }
        if (::stateFile.isInitialized) stateFile.delete()
        if (::server.isInitialized) server.shutdown()
    }

    @Test fun portableExportReplaceAllRestoresTracksAndProviderUrlWithoutLeakingToken() = runBlocking {
        val original = seedPlaylist()
        connections.update(SourceType.JELLYFIN) { StoredConnection(SourceType.JELLYFIN, serverUrl = server.target + "/original", token = "no-export") }
        export(ExportMode.PORTABLE)
        assertFalse(stateFile.readText().contains("no-export"))
        assertEquals("any-player-state", AppJson.decodeFromString<AnyPlayerStateEnvelope>(stateFile.readText()).format)
        playlists.delete(original.id)
        playlists.create("Remove me", PlaylistType.STANDARD)
        connections.update(SourceType.JELLYFIN) { it!!.copy(serverUrl = server.target + "/changed", token = "local-token") }
        import(MergePolicy.REPLACE_ALL, expected = summary("Import", 1, 0, 1, 0, 1))
        assertEquals(listOf("Exported collection"), playlists.customPlaylists.first().map { it.name })
        assertEquals(listOf("Round trip song"), playlists.observeTracks(original.id).first().map { it.title })
        assertEquals(server.target + "/original", connections.awaitLoaded()[SourceType.JELLYFIN]!!.serverUrl)
        assertEquals("local-token", connections.awaitLoaded()[SourceType.JELLYFIN]!!.token)
    }

    @Test fun privateExportEncryptsAndRestoresProviderCredentialsAndPlaylist() = runBlocking {
        val original = seedPlaylist()
        connections.update(SourceType.PLEX) { StoredConnection(SourceType.PLEX, serverUrl = server.target, token = "px-private") }
        export(ExportMode.PRIVATE, "correct horse")
        val encoded = stateFile.readText()
        assertFalse(encoded.contains("px-private"))
        assertFalse(encoded.contains("Exported collection"))
        assertTrue(AppJson.decodeFromString<EncryptedStateFile>(encoded).ciphertextBase64.isNotBlank())
        playlists.delete(original.id)
        connections.update(SourceType.PLEX) { null }
        import(MergePolicy.REPLACE_ALL, passphrase = "correct horse", expected = summary("Import", 1, 0, 1, 0, 1))
        assertEquals("px-private", connections.awaitLoaded()[SourceType.PLEX]!!.token)
        assertEquals("Round trip song", playlists.observeTracks(original.id).first().single().title)
    }

    @Test fun mergeKeepLocalRetainsEditedPlaylistAndAddsImportedCopy() = runBlocking {
        val original = seedPlaylist()
        export(ExportMode.PORTABLE)
        playlists.rename(original.id, "Edited locally")
        import(MergePolicy.MERGE_KEEP_LOCAL, expected = summary("Import", 1, 0, 1, 0))
        val restored = playlists.customPlaylists.first()
        assertEquals(setOf("Edited locally", "Exported collection"), restored.map { it.name }.toSet())
        assertEquals("Edited locally", playlists.observePlaylist(original.id).first()!!.name)
        restored.forEach { assertEquals("Round trip song", playlists.observeTracks(it.id).first().single().title) }
    }

    @Test fun mergePreferImportUpdatesConflictAndPreservesLocalOnlyPlaylist() = runBlocking {
        val original = seedPlaylist()
        export(ExportMode.PORTABLE)
        playlists.rename(original.id, "Edited locally")
        val localOnly = playlists.create("Local only", PlaylistType.STANDARD)
        import(MergePolicy.MERGE_PREFER_IMPORT, expected = summary("Import", 0, 1, 0, 1))
        assertEquals(setOf(original.id, localOnly.id), playlists.customPlaylists.first().map { it.id }.toSet())
        assertEquals("Exported collection", playlists.observePlaylist(original.id).first()!!.name)
        assertEquals("Round trip song", playlists.observeTracks(original.id).first().single().title)
    }

    @Test fun dryRunPreviewsReplaceWithoutChangingPlaylistsOrConnections() = runBlocking {
        val original = seedPlaylist()
        connections.update(SourceType.JELLYFIN) { StoredConnection(SourceType.JELLYFIN, serverUrl = server.target + "/exported", token = "keep-token") }
        export(ExportMode.PORTABLE)
        playlists.rename(original.id, "Keep edited")
        connections.update(SourceType.JELLYFIN) { it!!.copy(serverUrl = server.target + "/local") }
        import(MergePolicy.REPLACE_ALL, dryRun = true, expected = summary("Dry run", 0, 1, 0, 1, 1))
        assertEquals("Keep edited", playlists.observePlaylist(original.id).first()!!.name)
        assertEquals(server.target + "/local", connections.awaitLoaded()[SourceType.JELLYFIN]!!.serverUrl)
        assertEquals("keep-token", connections.awaitLoaded()[SourceType.JELLYFIN]!!.token)
    }

    @Test fun configDryRunThenImportRestoresPlaylistAndUrlPreservingAuth() = runBlocking {
        val original = seedPlaylist()
        val exported = config.exportPlaylistsJson()
        playlists.delete(original.id)
        connections.update(SourceType.PLEX) { StoredConnection(SourceType.PLEX, serverUrl = server.target + "/local", token = "px-local") }
        stateFile.writeText(buildJsonObject {
            put("export_version", 1)
            put("custom_playlists", exported)
            put("provider_configs", buildJsonObject { put("plex", buildJsonObject { put("base_url", server.target + "/imported") }) })
        }.toString())
        import(MergePolicy.REPLACE_ALL, dryRun = true, configFile = true, expected = summary("Config dry run", 1, 0, 1, 0, 1))
        assertTrue(playlists.customPlaylists.first().isEmpty())
        assertEquals(server.target + "/local", connections.awaitLoaded()[SourceType.PLEX]!!.serverUrl)
        import(MergePolicy.REPLACE_ALL, configFile = true, expected = summary("Config import", 1, 0, 1, 0, 1))
        assertEquals("Exported collection", playlists.customPlaylists.first().single().name)
        assertEquals("Round trip song", playlists.observeTracks(original.id).first().single().title)
        assertEquals(server.target + "/imported", connections.awaitLoaded()[SourceType.PLEX]!!.serverUrl)
        assertEquals("px-local", connections.awaitLoaded()[SourceType.PLEX]!!.token)
    }

    private suspend fun seedPlaylist(): CustomPlaylist = playlists.create("Exported collection", PlaylistType.STANDARD).also {
        playlists.addTrack(it.id, server.track(1, "Round trip song"))
    }

    private suspend fun export(mode: ExportMode, passphrase: String? = null) {
        onMain { vm.export(Uri.fromFile(stateFile).toString(), mode, passphrase) }
        awaitUntil { !vm.state.value.transferBusy }
        visibleStatus("Export complete")
        assertTrue(stateFile.length() > 0)
    }

    private suspend fun import(policy: MergePolicy, passphrase: String? = null, dryRun: Boolean = false,
        configFile: Boolean = false, expected: String) {
        onMain { vm.import(Uri.fromFile(stateFile).toString(), policy, passphrase, dryRun, configFile) }
        awaitUntil { !vm.state.value.transferBusy }
        assertEquals(expected, vm.state.value.transferStatus)
        visibleStatus(expected)
    }

    private fun visibleStatus(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertExists()
    }

    private fun summary(prefix: String, playlistsAdded: Int, playlistsUpdated: Int, tracksAdded: Int,
        tracksUpdated: Int, connections: Int = 0) =
        "$prefix complete. playlists +$playlistsAdded/~$playlistsUpdated, tracks +$tracksAdded/~$tracksUpdated, union +0/~0, connections +$connections/skip 0, no warnings"
}
