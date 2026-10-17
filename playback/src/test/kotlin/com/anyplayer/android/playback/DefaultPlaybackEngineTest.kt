package com.anyplayer.android.playback

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.AuthRepository
import com.anyplayer.android.data.settings.*
import com.anyplayer.android.playback.backend.*
import com.anyplayer.android.playback.persistence.PlaybackPersistence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPlaybackEngineTest {
    private fun song(id: String, source: SourceType = SourceType.CUSTOM) = Track(id, id, "Artist", durationMs = 10_000, source = source,
        url = if (source == SourceType.SPOTIFY) null else "http://localhost/$id.wav")
    private class Store : PlayerSettingsStore {
        override val settings = MutableStateFlow(PlayerSettings())
        override suspend fun awaitLoaded() = settings.value
        override fun update(transform: (PlayerSettings) -> PlayerSettings) { settings.value = transform(settings.value) }
    }
    private class Persistence(var saved: QueueState = QueueState()) : PlaybackPersistence {
        var contents = 0; var cursors = 0
        override suspend fun restore() = saved
        override suspend fun writeContent(queue: QueueState) { saved = queue; contents++ }
        override suspend fun writeCursor(queue: QueueState) { cursors++ }
    }
    private class Auth : AuthRepository {
        override val profiles = MutableStateFlow(emptyMap<SourceType, ProviderConnectionProfile>())
        var connected = true
        override fun isSourceConnected(source: SourceType?) = connected
        override suspend fun connectJellyfin(serverUrl: String, apiKey: String, pageSize: Int): ProviderConnectionProfile = error("unused")
        override suspend fun connectPlex(serverUrl: String, token: String, pageSize: Int): ProviderConnectionProfile = error("unused")
        override suspend fun beginSpotifyAuth(): String = error("unused")
        override suspend fun completeSpotifyAuth(redirectUri: String): ProviderConnectionProfile = error("unused")
        override suspend fun disconnect(source: SourceType) = Unit
        override suspend fun updatePageSize(source: SourceType, pageSize: Int) = false
        override suspend fun restore() = emptyList<ProviderConnectionProfile>()
    }
    private class Backend(override val kind: BackendKind) : PlaybackBackend {
        override val events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 64)
        var id = -1L; var run: Run? = null; var loads = 0; var stops = 0; var pauses = 0; var plays = 0; var updates = 0
        var position = 0L; var repeat = false; var volume = 100; var block: CompletableDeferred<Unit>? = null
        private fun PlanItem.itemKey(): ItemKey = when (this) { is PlanItem.Song -> ItemKey.Song(entry.uid); is PlanItem.Break -> ItemKey.Break(breakId) }
        override suspend fun load(loadId: Long, run: Run, startIndex: Int, positionMs: Long, playWhenReady: Boolean) {
            loads++; block?.await(); id = loadId; this.run = run; position = positionMs
            events.emit(BackendEvent.ItemStarted(id, run.items[startIndex].itemKey()))
            events.emit(BackendEvent.PlayingChanged(id, playWhenReady, false, false))
        }
        override suspend fun updateUpcoming(loadId: Long, run: Run, currentKey: ItemKey) { updates++; this.run = run }
        override suspend fun seekToItem(key: ItemKey, positionMs: Long) { position = positionMs }
        override suspend fun play() { plays++; events.emit(BackendEvent.PlayingChanged(id, true, false, false)) }
        override suspend fun pause() { pauses++; events.emit(BackendEvent.PlayingChanged(id, false, false, false)) }
        override suspend fun seekTo(ms: Long) { position = ms }
        override suspend fun setVolume(effectivePercent: Int) { volume = effectivePercent }
        override suspend fun setRepeatOne(on: Boolean) { repeat = on }
        override suspend fun stop() { stops++ }
        override fun positionMs() = position
        override fun durationMs() = 10_000L
    }
    private class Fixture(val test: TestScope, saved: QueueState = QueueState(), spotify: Boolean = true) {
        val exo = Backend(BackendKind.EXO); val remote = Backend(BackendKind.SPOTIFY)
        val auth = Auth(); val store = Store(); val persistence = Persistence(saved)
        val changes = mutableListOf<EngineChange>(); val events = mutableListOf<EngineEvent>()
        val engine = DefaultPlaybackEngine(if (spotify) mapOf(BackendKind.EXO to exo, BackendKind.SPOTIFY to remote) else mapOf(BackendKind.EXO to exo),
            auth, persistence, store, test.backgroundScope, { test.testScheduler.currentTime }, {})
        init {
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { engine.changes.toList(changes) }
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { engine.events.toList(events) }
        }
    }
    @Test fun staleLoadEventsNeverRewindCursor() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a"), song("b"), song("c"))); runCurrent()
        val old = f.exo.id
        f.engine.next(); runCurrent()
        f.exo.events.emit(BackendEvent.ItemStarted(old, ItemKey.Song(1)))
        f.exo.events.emit(BackendEvent.RunEnded(old)); runCurrent()
        assertEquals("b", f.engine.queue.value.current?.track?.id)
    }
    @Test fun concurrentNextAndRunEndedAdvanceOnlyOnce() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a"), song("b"), song("c"))); runCurrent()
        val old = f.exo.id
        f.engine.next(); f.exo.events.emit(BackendEvent.RunEnded(old)); runCurrent()
        assertEquals("b", f.engine.queue.value.current?.track?.id)
    }
    @Test fun playerPauseIsAdoptedAndNotOverriddenByQueueEdit() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a"), song("b"))); runCurrent()
        f.exo.events.emit(BackendEvent.PlayingChanged(f.exo.id, false, false, true)); runCurrent()
        f.engine.playNext(song("c")); runCurrent()
        assertFalse(f.engine.player.value.playRequested)
        assertEquals(PlaybackStateType.PAUSED, f.engine.player.value.status)
        assertEquals(0, f.exo.plays)
    }
    @Test fun breakPlaysOnAdvanceButJumpDropsIt() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a"), song("b"))); runCurrent()
        val target = f.engine.queue.value.entryAt(1).uid
        f.engine.setPendingBreak(PendingBreak(target, "dj", "file:///dj.wav")); runCurrent()
        f.engine.next(); runCurrent()
        assertEquals("dj", f.engine.player.value.activeBreak?.breakId)
        assertEquals("a", f.engine.queue.value.current?.track?.id)
        assertTrue(f.events.contains(EngineEvent.BreakStarted("dj")))
        f.engine.next(); runCurrent()
        assertEquals("b", f.engine.queue.value.current?.track?.id)
        assertTrue(f.events.contains(EngineEvent.BreakEnded("dj")))
        f.engine.previous(); runCurrent()
        f.engine.setPendingBreak(PendingBreak(target, "jump", "file:///dj.wav")); runCurrent()
        f.engine.playEntry(target); runCurrent()
        assertNull(f.engine.player.value.activeBreak)
        assertTrue(f.events.contains(EngineEvent.BreakDropped("jump")))
    }
    @Test fun previousDuringBreakRestartsPrecedingSong() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a"), song("b"))); runCurrent()
        f.engine.setPendingBreak(PendingBreak(2, "dj", "file:///dj.wav")); runCurrent()
        f.engine.next(); runCurrent(); f.engine.previous(); runCurrent()
        assertEquals("a", f.engine.queue.value.current?.track?.id)
        assertNull(f.engine.player.value.activeBreak)
        assertEquals(0, f.engine.positionMs())
        assertTrue(f.events.contains(EngineEvent.BreakEnded("dj")))
        assertFalse(f.events.contains(EngineEvent.BreakDropped("dj")))
    }
    @Test fun gateBlocksAllPlayOriginsAndPausesOnce() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a", SourceType.JELLYFIN))); runCurrent()
        f.auth.connected = false
        f.auth.profiles.value = mapOf(SourceType.JELLYFIN to ProviderConnectionProfile(SourceType.JELLYFIN, connected = false, hasToken = false)); runCurrent()
        assertFalse(f.engine.player.value.playRequested)
        assertEquals(1, f.exo.pauses)
        f.engine.play(ChangeOrigin.SYNC); f.engine.togglePlayPause(); f.engine.play(); runCurrent()
        assertFalse(f.engine.player.value.playRequested)
        assertEquals(1, f.exo.pauses)
        assertEquals("Playback disabled: jellyfin is not configured/authenticated. Reconnect it in Settings. Next/Previous still works.", f.engine.player.value.disabledReason)
    }
    @Test fun repeatAllWrapsAcrossBackendRunsWithoutDroppingPlayIntent() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setRepeat(RepeatMode.ALL)
        f.engine.setQueue(listOf(song("a"), song("1234567890123456789012", SourceType.SPOTIFY))); runCurrent()
        f.exo.events.emit(BackendEvent.RunEnded(f.exo.id)); runCurrent()
        assertEquals(SourceType.SPOTIFY, f.engine.queue.value.current?.track?.source)
        assertTrue(f.engine.player.value.playRequested)
        assertEquals(1, f.exo.stops)
        f.remote.events.emit(BackendEvent.RunEnded(f.remote.id)); runCurrent()
        assertEquals("a", f.engine.queue.value.current?.track?.id)
        assertTrue(f.engine.player.value.playRequested)
    }
    @Test fun unplayableEntriesSkippedInsideRunAndAtStart() = runTest {
        val f = Fixture(this); runCurrent()
        val bad = song("bad").copy(url = null)
        f.engine.setQueue(listOf(bad, song("a"), bad, song("b"))); runCurrent()
        assertEquals("a", f.engine.queue.value.current?.track?.id)
        assertEquals(listOf("a", "b"), f.exo.run?.items?.filterIsInstance<PlanItem.Song>()?.map { it.entry.track.id })
        f.engine.next(); runCurrent(); assertEquals("b", f.engine.queue.value.current?.track?.id)
    }
    @Test fun naturalTransitionsEmitUserChangesAndCommandsKeepOrigins() = runTest {
        val f = Fixture(this); runCurrent()
        assertTrue(f.changes.any { it.origin == ChangeOrigin.RESTORE })
        f.engine.setQueue(listOf(song("a"), song("b")), autoPlay = false, origin = ChangeOrigin.SYNC); runCurrent()
        assertTrue(f.changes.any { it.origin == ChangeOrigin.SYNC && it.contentChanged })
        f.engine.play(); runCurrent(); f.changes.clear()
        f.exo.events.emit(BackendEvent.ItemStarted(f.exo.id, ItemKey.Song(2))); runCurrent()
        f.exo.events.emit(BackendEvent.PositionDiscontinuity(f.exo.id, 1500, 10_000)); runCurrent()
        assertTrue(f.changes.isNotEmpty())
        assertTrue(f.changes.all { it.origin == ChangeOrigin.USER })
    }
    @Test fun restoreDoesNotLoadBackendAndPositionAppliedOnFirstPlay() = runTest {
        val saved = QueueOps.replace(QueueState(), listOf(song("a"), song("b")), 1, kotlin.random.Random(0))
        val f = Fixture(this, saved); f.store.settings.value = PlayerSettings(positionMs = 2500); runCurrent()
        f.engine.awaitRestored()
        assertEquals("b", f.engine.queue.value.current?.track?.id)
        assertEquals(0, f.exo.loads); assertFalse(f.engine.player.value.playRequested)
        f.engine.play(); runCurrent()
        assertEquals(2500L, f.exo.position)
    }
    @Test fun noMidRequestCancellationLatestIntentAppliedAfterLoad() = runTest {
        val f = Fixture(this); runCurrent()
        val blocked = CompletableDeferred<Unit>(); f.exo.block = blocked
        f.engine.setQueue(listOf(song("a"), song("b"))); runCurrent()
        f.engine.next(); runCurrent()
        assertEquals(1, f.exo.loads)
        blocked.complete(Unit); runCurrent()
        assertEquals("b", f.engine.queue.value.current?.track?.id)
        assertEquals(2, f.exo.loads)
        assertTrue(f.engine.player.value.playRequested)
    }
    @Test fun missingSpotifyBindingSurfacesError() = runTest {
        val f = Fixture(this, spotify = false); runCurrent()
        f.engine.setQueue(listOf(song("1234567890123456789012", SourceType.SPOTIFY))); runCurrent()
        assertEquals(PlaybackStateType.ERROR, f.engine.player.value.status)
        assertEquals("Spotify playback is not available in this build", f.engine.player.value.error)
    }
    @Test fun contentPersistenceDebouncesAndCursorDoesNotRewriteContent() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a"), song("b"))); runCurrent()
        f.engine.playNext(song("c")); runCurrent()
        advanceTimeBy(500); runCurrent(); assertEquals(1, f.persistence.contents)
        f.engine.next(); runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(1, f.persistence.contents)
        assertTrue(f.persistence.cursors > 0)
    }
    @Test fun naturalItemStartedSlidesUpcomingWithoutReload() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue((1..75).map { song("$it") }); runCurrent()
        f.exo.events.emit(BackendEvent.ItemStarted(f.exo.id, ItemKey.Song(2))); runCurrent()
        assertEquals(1, f.exo.loads)
        assertEquals(51L, (f.exo.run!!.items.last() as PlanItem.Song).entry.uid)
        assertTrue(f.exo.updates > 0)
    }
    @Test fun exhaustedExoItemSkipsToNextSong() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a"), song("b"))); runCurrent()
        f.exo.events.emit(BackendEvent.Error(f.exo.id, ItemKey.Song(1), "Decoder failed")); runCurrent()
        assertEquals("b", f.engine.queue.value.current?.track?.id)
        assertTrue(f.engine.player.value.playRequested)
    }
    @Test fun nextAtEndWithoutRepeatDoesNotRestartOrPauseSong() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a"))); runCurrent()
        f.engine.seekTo(2000); runCurrent()
        f.engine.next(); runCurrent()
        assertEquals(1, f.exo.loads)
        assertTrue(f.engine.player.value.playRequested)
        assertEquals(2000L, f.engine.positionMs())
    }
    @Test fun completedRunKeepsEndPositionAndStopsIntent() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a"))); runCurrent()
        f.exo.position = 10_000
        f.exo.events.emit(BackendEvent.RunEnded(f.exo.id)); runCurrent()
        assertEquals(1, f.exo.loads)
        assertEquals(10_000L, f.engine.positionMs())
        assertFalse(f.engine.player.value.playRequested)
    }
    @Test fun repeatOneIsResetBeforeNextBackendLoad() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setRepeat(RepeatMode.ONE)
        f.engine.setQueue(listOf(song("a"), song("b"))); runCurrent()
        assertTrue(f.exo.repeat)
        f.engine.setRepeat(RepeatMode.OFF); f.engine.next(); runCurrent()
        assertFalse(f.exo.repeat)
    }
    @Test fun replacedAndClearedBreaksEmitDropped() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a"), song("b"))); runCurrent()
        f.engine.setPendingBreak(PendingBreak(2, "old", "file:///old.wav")); runCurrent()
        f.engine.setPendingBreak(PendingBreak(2, "new", "file:///new.wav")); runCurrent()
        f.engine.setPendingBreak(null); runCurrent()
        assertEquals(1, f.events.count { it == EngineEvent.BreakDropped("old") })
        assertEquals(1, f.events.count { it == EngineEvent.BreakDropped("new") })
    }
    @Test fun skippedUnplayableSelectionSurfacesWarningWithoutStoppingPlayback() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("bad").copy(url = null), song("a"))); runCurrent()
        assertEquals("Can't play \"bad\"", f.engine.player.value.error)
        assertEquals(PlaybackStateType.PLAYING, f.engine.player.value.status)
    }
    @Test fun clearingPendingWhileCoordinatorPreparesDoesNotCancelActiveBreak() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a"), song("b"))); runCurrent()
        f.engine.setPendingBreak(PendingBreak(2, "dj", "file:///dj.wav")); runCurrent()
        f.engine.next(); runCurrent()
        f.engine.setPendingBreak(null); runCurrent()
        assertEquals("dj", f.engine.player.value.activeBreak?.breakId)
        assertEquals("a", f.engine.queue.value.current?.track?.id)
        assertFalse(f.events.contains(EngineEvent.BreakDropped("dj")))
    }
    @Test fun terminalErrorSurvivesBackendPauseAcknowledgement() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a"))); runCurrent()
        f.exo.events.emit(BackendEvent.Error(f.exo.id, ItemKey.Song(1), "Decoder failed")); runCurrent()
        assertEquals(PlaybackStateType.ERROR, f.engine.player.value.status)
        assertFalse(f.engine.player.value.playRequested)
        assertEquals("Decoder failed", f.engine.player.value.error)
    }
    @Test fun playNextStackSurvivesSameItemStartedAcknowledgement() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a"), song("b"))); runCurrent()
        f.engine.playNext(song("first")); runCurrent()
        f.exo.events.emit(BackendEvent.ItemStarted(f.exo.id, ItemKey.Song(1))); runCurrent()
        f.engine.playNext(song("second")); runCurrent()
        assertEquals(listOf("first", "second", "b"), f.engine.queue.value.upcoming().map { it.track.id })
    }
    @Test fun previousSkipsUnplayableEntriesBackwards() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a"), song("bad").copy(url = null), song("b")), startIndex = 2); runCurrent()
        f.engine.previous(); runCurrent()
        assertEquals("a", f.engine.queue.value.current?.track?.id)
    }
    @Test fun previousAtStartSeeksWithoutResumingPausedQueue() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setQueue(listOf(song("a")), autoPlay = false); runCurrent()
        f.engine.previous(); runCurrent()
        assertFalse(f.engine.player.value.playRequested)
        assertEquals(0, f.exo.loads)
    }
    @Test fun slowSyncCollectorDoesNotLoseMutationChanges() = runTest {
        val f = Fixture(this); runCurrent()
        val observed = mutableListOf<EngineChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.engine.changes.collect { observed += it; delay(1) }
        }
        repeat(300) { f.engine.setVolume(it % 101) }
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertTrue("Only ${observed.size} of 300 mutations reached slow collector", observed.size >= 300)
    }
}

