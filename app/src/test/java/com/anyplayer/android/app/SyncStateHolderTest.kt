package com.anyplayer.android.app

import com.anyplayer.android.core.model.AudioNormalizationSettings
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import com.anyplayer.android.feature.state.transfer.ConfigFileExporter
import com.anyplayer.android.feature.state.transfer.ConfigFileImporter
import com.anyplayer.android.feature.sync.AppStateSyncPayload
import com.anyplayer.android.feature.sync.SyncPreferences
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import com.anyplayer.android.feature.sync.SyncSnapshotClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SyncStateHolderTest {
    private val syncPreferencesStore: SyncPreferencesStore = mock()
    private val syncSnapshotClient: SyncSnapshotClient = mock()
    private val playbackQueueManager: PlaybackQueueManager = mock()
    private val configFileImporter: ConfigFileImporter = mock()
    private val configFileExporter: ConfigFileExporter = mock()
    private val status = MutableStateFlow(
        PlaybackStatus(
            state = PlaybackStateType.PLAYING,
            shuffle = false,
            repeatMode = RepeatMode.OFF,
            volume = 75,
            currentTrack = track("local"),
            position = 10_000L,
            duration = 180_000L,
            queue = listOf(track("local"), track("next"))
        )
    )

    @Test
    fun pullSyncState_clearsPlaybackWhenRemoteAppStateIsEmptyAndStopped() = runTest {
        val holder = holder(
            SyncPreferences(
                serverTarget = "http://sync",
                syncAppState = true,
                syncPlaylists = false,
                syncProviderConfiguration = false,
                syncSettings = false
            )
        )
        whenever(syncSnapshotClient.fetchSnapshot("http://sync")).thenReturn(
            JsonObject(
                mapOf(
                    "app_state" to JsonObject(
                        mapOf(
                            "current_track" to JsonNull,
                            "queue" to JsonArray(emptyList()),
                            "state" to JsonPrimitive("stopped")
                        )
                    )
                )
            )
        )

        holder.pullSyncState(confirmPlaylistOverwrite = false)
        advanceUntilIdle()

        verify(playbackQueueManager).setQueue(emptyList(), startIndex = 0, autoPlay = false)
        verify(playbackQueueManager).pause()
    }

    @Test
    fun connectToSyncServer_reportsPartialFailureWhenAnEnabledPushFails() = runTest {
        val holder = holder(
            SyncPreferences(
                serverTarget = "http://sync",
                syncAppState = true,
                syncPlaylists = false,
                syncProviderConfiguration = false,
                syncSettings = true
            )
        )
        val payload = AppStateSyncPayload("stopped", false, "off", 75, 0L, 0L)
        whenever(syncSnapshotClient.fetchSnapshot("http://sync")).thenReturn(JsonObject(emptyMap()))
        whenever(syncSnapshotClient.payloadFromPlayback(status.value)).thenReturn(payload)
        whenever(syncSnapshotClient.pushAppState("http://sync", payload)).thenReturn(true)
        whenever(syncSnapshotClient.pushNamespace(eq("http://sync"), eq("settings"), any())).thenReturn(false)

        holder.connectToSyncServer()
        advanceUntilIdle()

        assertEquals("Connected, but some local data failed to push.", holder.syncStatus.value)
    }

    @Test
    fun resolveSyncConflict_reportsFailureWhenLocalPushFails() = runTest {
        val holder = holder(
            SyncPreferences(
                serverTarget = "http://sync",
                syncAppState = true,
                syncPlaylists = false,
                syncProviderConfiguration = false,
                syncSettings = false
            )
        )
        val payload = AppStateSyncPayload("stopped", false, "off", 75, 0L, 0L)
        whenever(syncSnapshotClient.payloadFromPlayback(status.value)).thenReturn(payload)
        whenever(syncSnapshotClient.pushAppState("http://sync", payload)).thenReturn(false)

        holder.resolveSyncConflict(useLocal = true)
        advanceUntilIdle()

        assertEquals("Some local data failed to push to server.", holder.syncStatus.value)
    }

    private fun TestScope.holder(preferences: SyncPreferences): SyncStateHolder {
        whenever(syncPreferencesStore.read()).doReturn(preferences)
        whenever(playbackQueueManager.status).doReturn(status)
        whenever(playbackQueueManager.audioNormalizationSettings).doReturn(
            MutableStateFlow(AudioNormalizationSettings())
        )
        return SyncStateHolder(
            viewModelScope = this,
            syncPreferencesStore = syncPreferencesStore,
            syncSnapshotClient = syncSnapshotClient,
            playbackQueueManager = playbackQueueManager,
            configFileImporter = configFileImporter,
            configFileExporter = configFileExporter,
            customPlaylistCount = { 0 },
            applyImportSummary = { _, _ -> },
            onSyncApplied = {}
        )
    }

    private fun track(id: String): Track = Track(
        id = id,
        title = "Track $id",
        artist = "Artist",
        source = SourceType.CUSTOM
    )
}
