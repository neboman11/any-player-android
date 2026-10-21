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
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** State-file import of the exported player flags (volume, shuffle, repeat) through the real engine and player settings.
 * Same harness as [SettingsTransferTest]: only SAF selection is bypassed. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TransferPlaybackStateTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val notifications = Device.notificationPermission()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    @Inject lateinit var engine: Provider<PlaybackEngine>
    @Inject lateinit var sync: Provider<SyncEngine>
    @Inject lateinit var syncSettings: SyncSettingsStore
    @Inject lateinit var playerSettings: PlayerSettingsStore
    @Inject lateinit var playlists: PlaylistRepository
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
        stateFile = File.createTempFile("transfer-playback-", ".json", Device.context.cacheDir)
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

    @Test fun replaceAllImportRestoresExportedVolumeShuffleAndRepeat() = runBlocking {
        seedPlaylist()
        exportPlayback(volume = 37, shuffle = true, repeat = RepeatMode.ALL)
        val exported = AppJson.decodeFromString<AnyPlayerStateEnvelope>(stateFile.readText()).data.playbackState!!
        assertEquals(37, exported.volume)
        assertTrue(exported.shuffle)
        assertEquals(RepeatMode.ALL, exported.repeatMode)
        changeLocally(volume = 82, shuffle = false, repeat = RepeatMode.OFF)
        import(MergePolicy.REPLACE_ALL, expected = summary("Import", 0, 1, 0, 1))
        awaitUntil(message = "engine restores exported flags") { flags() == Triple(37, true, RepeatMode.ALL) }
        assertEquals("Import must restore exported volume", 37, engine.get().player.value.volume)
        awaitUntil(message = "player settings persist exported flags") {
            playerSettings.settings.value.let { Triple(it.volume, it.shuffle, it.repeat) } == Triple(37, true, RepeatMode.ALL)
        }
    }

    @Test fun dryRunAndKeepLocalLeaveLocalPlaybackSettingsAlone() = runBlocking {
        exportPlayback(volume = 37, shuffle = true, repeat = RepeatMode.ALL)
        changeLocally(volume = 82, shuffle = false, repeat = RepeatMode.OFF)
        import(MergePolicy.REPLACE_ALL, dryRun = true, expected = summary("Dry run", 0, 0, 0, 0))
        import(MergePolicy.MERGE_KEEP_LOCAL, expected = summary("Import", 0, 0, 0, 0))
        // Engine commands are dispatched to Main: a probe command queued after the imports proves none changed the flags.
        onMain { engine.get().setVolume(81) }
        awaitUntil(message = "probe volume applied") { engine.get().player.value.volume == 81 }
        assertEquals(Triple(81, false, RepeatMode.OFF), flags())
        assertEquals(Triple(81, false, RepeatMode.OFF), playerSettings.settings.value.let { Triple(it.volume, it.shuffle, it.repeat) })
    }

    private fun flags() = Triple(engine.get().player.value.volume, engine.get().queue.value.shuffle, engine.get().queue.value.repeat)

    private suspend fun exportPlayback(volume: Int, shuffle: Boolean, repeat: RepeatMode) {
        val song = server.track(2, "Exported queue song")
        onMain { engine.get().setQueue(listOf(song), autoPlay = false) }
        changeLocally(volume, shuffle, repeat)
        onMain { vm.export(Uri.fromFile(stateFile).toString(), ExportMode.PORTABLE, null) }
        awaitUntil { !vm.state.value.transferBusy }
        visibleStatus("Export complete")
    }

    private suspend fun changeLocally(volume: Int, shuffle: Boolean, repeat: RepeatMode) {
        onMain { engine.get().setVolume(volume); engine.get().setShuffle(shuffle); engine.get().setRepeat(repeat) }
        awaitUntil(message = "local flags applied") { flags() == Triple(volume, shuffle, repeat) }
    }

    private suspend fun seedPlaylist(): CustomPlaylist = playlists.create("Exported collection", PlaylistType.STANDARD).also {
        playlists.addTrack(it.id, server.track(1, "Round trip song"))
    }

    private suspend fun import(policy: MergePolicy, dryRun: Boolean = false, expected: String) {
        onMain { vm.import(Uri.fromFile(stateFile).toString(), policy, null, dryRun, false) }
        awaitUntil { !vm.state.value.transferBusy }
        assertEquals(expected, vm.state.value.transferStatus)
        visibleStatus(expected)
    }

    private fun visibleStatus(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertExists()
    }

    private fun summary(prefix: String, playlistsAdded: Int, playlistsUpdated: Int, tracksAdded: Int, tracksUpdated: Int) =
        "$prefix complete. playlists +$playlistsAdded/~$playlistsUpdated, tracks +$tracksAdded/~$tracksUpdated, union +0/~0, connections +0/skip 0, no warnings"
}
