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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.never
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
    fun startRealtimePlaybackSync_reconnectsWhenAuthTokenChanges() = runTest {
        val holder = holder(
            SyncPreferences(serverTarget = "http://sync", authToken = "old-token"),
            backgroundScope
        )
        whenever(syncSnapshotClient.getClientId()).thenReturn("local-client")
        whenever(syncSnapshotClient.payloadFromPlayback(status.value)).thenReturn(
            AppStateSyncPayload("playing", false, "off", 75, 10_000L, 180_000L)
        )
        whenever(syncSnapshotClient.observeStateUpdates("http://sync"))
            .thenReturn(MutableSharedFlow())

        holder.startRealtimePlaybackSync()
        runCurrent()
        verify(syncSnapshotClient).observeStateUpdates("http://sync")

        holder.updateSyncAuthToken("new-token")
        runCurrent()
        verify(syncSnapshotClient, times(2)).observeStateUpdates("http://sync")
    }

    @Test
    fun startRealtimePlaybackSync_doesNotPublishInitialEmptyPlayback() = runTest {
        status.value = status.value.copy(currentTrack = null, queue = emptyList(), state = PlaybackStateType.IDLE)
        val holder = holder(SyncPreferences(serverTarget = "http://sync"), backgroundScope)
        whenever(syncSnapshotClient.getClientId()).thenReturn("local-client")
        whenever(syncSnapshotClient.observeStateUpdates("http://sync")).thenReturn(MutableSharedFlow())

        holder.startRealtimePlaybackSync()
        runCurrent()

        verify(syncSnapshotClient, never()).pushAppState(any(), any())
    }

    @Test
    fun startRealtimePlaybackSync_publishesClearAfterQueueWasLoaded() = runTest {
        val holder = holder(SyncPreferences(serverTarget = "http://sync"), backgroundScope)
        whenever(syncSnapshotClient.getClientId()).thenReturn("local-client")
        whenever(syncSnapshotClient.observeStateUpdates("http://sync")).thenReturn(MutableSharedFlow())
        whenever(syncSnapshotClient.payloadFromPlayback(any())).thenAnswer { invocation ->
            val playback = invocation.getArgument<PlaybackStatus>(0)
            AppStateSyncPayload(
                if (playback.currentTrack == null) "stopped" else "playing",
                playback.shuffle, "off", playback.volume, playback.position, playback.duration,
                playback.currentTrack, playback.queue
            )
        }
        whenever(syncSnapshotClient.pushAppState(any(), any())).thenReturn(true)

        holder.startRealtimePlaybackSync()
        runCurrent()
        status.value = status.value.copy(currentTrack = null, queue = emptyList(), state = PlaybackStateType.IDLE)
        runCurrent()

        verify(syncSnapshotClient, times(2)).pushAppState(any(), any())
    }

    @Test
    fun startupPull_keepsLocalPlaybackWhenServerAppStateIsEmpty() = runTest {
        status.value = status.value.copy(shuffle = true)
        val holder = holder(SyncPreferences(serverTarget = "http://sync", syncAppState = true,
            syncPlaylists = false, syncProviderConfiguration = false, syncSettings = false))
        whenever(syncSnapshotClient.fetchSnapshot("http://sync")).thenReturn(
            JsonObject(mapOf("app_state" to JsonObject(mapOf(
                "current_track" to JsonNull,
                "queue" to JsonArray(emptyList()),
                "shuffle" to JsonPrimitive(false),
                "state" to JsonPrimitive("stopped")
            ))))
        )

        holder.pullSyncStateOnStartup()

        verify(playbackQueueManager, never()).setQueue(any(), any(), any())
        verify(playbackQueueManager, never()).setShuffle(false)
    }

    @Test
    fun startupPull_selectsRemoteTrackWithinSameQueue() = runTest {
        val mixedQueue = listOf(track("local"), track("next", SourceType.SPOTIFY))
        status.value = status.value.copy(shuffle = true, queue = mixedQueue)
        val holder = holder(SyncPreferences(serverTarget = "http://sync", syncAppState = true,
            syncPlaylists = false, syncProviderConfiguration = false, syncSettings = false))
        whenever(syncSnapshotClient.fetchSnapshot("http://sync")).thenReturn(
            appStateSnapshot(mixedQueue[1], mixedQueue)
        )

        holder.pullSyncStateOnStartup()

        verify(playbackQueueManager).setQueue(mixedQueue, startIndex = 1, autoPlay = false)
        verify(playbackQueueManager, never()).setShuffle(true)
    }

    @Test
    fun startupPull_doesNotRewindTrackThatAdvancedDuringFetch() = runTest {
        val mixedQueue = listOf(track("local"), track("next", SourceType.SPOTIFY))
        status.value = status.value.copy(shuffle = true, queue = mixedQueue)
        val holder = holder(SyncPreferences(serverTarget = "http://sync", syncAppState = true,
            syncPlaylists = false, syncProviderConfiguration = false, syncSettings = false))
        whenever(syncSnapshotClient.fetchSnapshot("http://sync")).thenAnswer {
            status.value = status.value.copy(currentTrack = mixedQueue[1])
            appStateSnapshot(mixedQueue[0], mixedQueue)
        }

        holder.pullSyncStateOnStartup()

        verify(playbackQueueManager, never()).playFromIndex(any())
        verify(playbackQueueManager, never()).setQueue(any(), any(), any())
    }

    @Test
    fun startupPull_doesNotDuplicateCurrentTrackInRemoteQueue() = runTest {
        val holder = holder(SyncPreferences(serverTarget = "http://sync", syncAppState = true,
            syncPlaylists = false, syncProviderConfiguration = false, syncSettings = false))
        val remoteQueue = listOf(track("other"), track("next"))
        whenever(syncSnapshotClient.fetchSnapshot("http://sync")).thenReturn(
            appStateSnapshot(track("other"), remoteQueue)
        )

        holder.pullSyncStateOnStartup()

        verify(playbackQueueManager).setQueue(remoteQueue, startIndex = 0, autoPlay = false)
    }

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

    private fun TestScope.holder(
        preferences: SyncPreferences,
        scope: CoroutineScope = this
    ): SyncStateHolder {
        whenever(syncPreferencesStore.read()).doReturn(preferences)
        whenever(playbackQueueManager.status).doReturn(status)
        whenever(playbackQueueManager.audioNormalizationSettings).doReturn(
            MutableStateFlow(AudioNormalizationSettings())
        )
        return SyncStateHolder(
            viewModelScope = scope,
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

    private fun track(id: String, source: SourceType = SourceType.CUSTOM): Track = Track(
        id = id,
        title = "Track $id",
        artist = "Artist",
        source = source
    )

    private fun appStateSnapshot(currentTrack: Track, queue: List<Track>) = JsonObject(mapOf(
        "app_state" to JsonObject(mapOf(
            "current_track" to Json.encodeToJsonElement(Track.serializer(), currentTrack),
            "queue" to JsonArray(queue.map { Json.encodeToJsonElement(Track.serializer(), it) }),
            "shuffle" to JsonPrimitive(true)
        ))
    ))
}
