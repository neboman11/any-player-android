package com.anyplayer.android.library

import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anyplayer.android.MainActivity
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.db.AppDatabase
import com.anyplayer.android.data.db.dao.providerOwnerKey
import com.anyplayer.android.data.db.entity.*
import com.anyplayer.android.data.playlists.PlaylistRepository
import com.anyplayer.android.playback.PlaybackEngine
import com.anyplayer.android.testing.Device
import com.anyplayer.android.testing.Device.awaitUntil
import com.anyplayer.android.testing.Device.onMain
import com.anyplayer.android.testing.TestServer
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real on-disk playlists, navigation and playback. Only audio/catalog responses are fixtures.
 * Long WAVs prevent natural track completion from racing UI gestures. Reveal/absence helpers
 * await UI effects of Room/IO work; Compose idling alone does not track those coroutines.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LibraryUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val notifications = Device.notificationPermission()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()

    @Inject lateinit var playlists: PlaylistRepository
    @Inject lateinit var database: AppDatabase
    @Inject lateinit var engineProvider: Provider<PlaybackEngine>

    private lateinit var server: TestServer
    private var app: ActivityScenario<MainActivity>? = null
    private val engine get() = engineProvider.get()
    private val lazyList get() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))

    @Before fun setUp() {
        hilt.inject()
        server = TestServer()
    }

    @After fun tearDown() {
        if (app != null) onMain { engine.pause() }
        app?.close()
        server.close()
    }

    @Test fun createDeleteCancelAndReopenPlaylists() = runBlocking {
        open()
        tab("PLAYLISTS")
        show("No provider playlists loaded")
        show("No custom playlists yet")
        create("Road mix", union = false)
        create("", union = true)
        show("Untitled Playlist")
        val saved = withTimeout(30_000) { playlists.customPlaylists.first { it.size == 2 } }
        assertEquals(PlaylistType.STANDARD, saved.single { it.name == "Road mix" }.playlistType)
        assertEquals(PlaylistType.UNION, saved.single { it.name == "Untitled Playlist" }.playlistType)
        reveal(hasContentDescription("Delete Road mix")).performClick()
        text("Delete playlist?").assertIsDisplayed()
        text("Cancel").performClick()
        show("Road mix")
        app!!.recreate()
        show("Road mix")
        app!!.close()
        app = null
        open()
        tab("PLAYLISTS")
        show("Road mix")
        show("Untitled Playlist")
        reveal(hasContentDescription("Delete Road mix")).performClick()
        text("Delete").performClick()
        withTimeout(30_000) { playlists.customPlaylists.first { it.size == 1 } }
        waitAbsent("Road mix")
        reveal(hasContentDescription("Delete Untitled Playlist")).performClick()
        text("Delete").performClick()
        show("No custom playlists yet")
    }

    @Test fun emptyStandardUnionAndMissingPlaylistStates() = runBlocking {
        val standard = seed("Empty standard")
        val union = playlists.create("Empty union", PlaylistType.UNION)
        openPlaylist(standard.name)
        show("No tracks found for this playlist")
        reveal(hasText("Play", substring = false)).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back").performClick()
        clickList(union.name)
        show("No source playlists added")
        clickList("Edit sources")
        await(hasText("Empty standard")).assertIsDisplayed()
        text("No playlists available. Refresh provider playlists or create a standard playlist.").assertDoesNotExist()
        text("Cancel").performClick()
        playlists.delete(standard.id)
        clickList("Edit sources")
        await(hasText("No playlists available. Refresh provider playlists or create a standard playlist.")).assertIsDisplayed()
        text("Cancel").performClick()
        clickList("Play")
        waitText("No tracks to play")
        // A detail destination whose id no longer exists must render its not-found state.
        playlists.delete(union.id)
        waitText("Playlist not found")
    }

    @Test fun addFromCachedProviderTableAndRemoveWithConfirmation() = runBlocking {
        val song = song("import", "Imported Song")
        cacheProvider(listOf(song))
        open()
        tab("PLAYLISTS")
        clickList("Cached catalog")
        rowAction("1. Imported Song", "Add to Playlist")
        text("No standard playlists yet. Create one in Playlists.").assertIsDisplayed()
        text("Cancel").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        create("Destination", false)
        clickList("Cached catalog")
        rowAction("1. Imported Song", "Add to Playlist")
        text("Add \"Imported Song\" to playlist").assertIsDisplayed()
        await(hasText("Destination")).performClick()
        waitText("Added to Destination")
        compose.onNodeWithContentDescription("Back").performClick()
        clickList("Destination")
        show("Tracks: 1")
        rowAction("1. Imported Song", "Remove")
        text("Remove track?").assertIsDisplayed()
        text("Cancel").performClick()
        show("1. Imported Song")
        rowAction("1. Imported Song", "Remove")
        compose.onNode(hasText("Remove") and hasAnyAncestor(isDialog())).performClick()
        show("Tracks: 0")
        show("No tracks found for this playlist")
    }

    @Test fun filterClearAndEveryColumnSortBothDirections() = runBlocking {
        val first = song("z", "Zulu", artist = "Alpha artist", album = "Zulu album", duration = 40_000)
        val second = song("a", "Alpha", artist = "Zulu artist", album = "Alpha album", duration = 20_000).copy(source = SourceType.JELLYFIN)
        seed("Sortable", listOf(first, second))
        openPlaylist("Sortable")
        reveal(hasText("Search tracks")).performTextInput("Zulu artist")
        hideKeyboard()
        show("2. Alpha")
        waitAbsent("1. Zulu")
        reveal(hasContentDescription("Clear search")).performClick()
        show("1. Zulu")
        val ascendingFirst = mapOf("Title" to "2. Alpha", "Artist" to "1. Zulu", "Album" to "2. Alpha", "Duration" to "2. Alpha", "Source" to "1. Zulu")
        for ((column, expected) in ascendingFirst) {
            clickList(column)
            assertRowOrder(expected, if (expected == "1. Zulu") "2. Alpha" else "1. Zulu")
            compose.onNodeWithContentDescription("Sorted ascending").assertExists()
            clickList(column)
            assertRowOrder(if (expected == "1. Zulu") "2. Alpha" else "1. Zulu", expected)
            compose.onNodeWithContentDescription("Sorted descending").assertExists()
        }
    }

    @Test fun distinctPersistsDeduplicatesPlaybackAndDuplicateRemovalConfirms() = runBlocking {
        val a = song("a", "Repeated", duration = 120_000)
        val b = song("b", "Finale", duration = 120_000)
        val playlist = seed("Duplicates mix", listOf(a, a.copy(id = "copy"), b))
        openPlaylist(playlist.name)
        reveal(hasContentDescription("Distinct playback")).performClick()
        withTimeout(30_000) { playlists.observePlaylist(playlist.id).first { it?.isDistinct == true } }
        show("Kept playback entry:")
        show("Duplicate occurrences:")
        clickList("Play")
        awaitUntil { engine.queue.value.entries.size == 2 && engine.player.value.status == PlaybackStateType.PLAYING }
        assertEquals(listOf("a", "b"), engine.queue.value.entries.map { it.track.id })
        assertTrue(server.requestsTo("/audio/a.wav").isNotEmpty())
        app!!.recreate()
        reveal(hasContentDescription("Distinct playback") and isOn()).assertIsOn()
        clickList("Remove")
        text("Remove duplicate?").assertIsDisplayed()
        text("Cancel").performClick()
        show("Tracks: 3")
        clickList("Remove")
        compose.onNode(hasText("Remove") and hasAnyAncestor(isDialog())).performClick()
        show("Tracks: 2")
        waitAbsent("Duplicate occurrences:")
        assertEquals(listOf("a", "b"), playlists.observeTracks(playlist.id).first().map { it.id })
    }

    @Test fun playInStoredOrderQueueNextAndCopyClipboard() = runBlocking {
        val songs = listOf(
            song("first", "First", duration = 120_000),
            song("second", "Second", duration = 120_000),
            song("third", "Third", duration = 120_000),
        )
        seed("Playable mix", songs)
        openPlaylist("Playable mix")
        clickList("Copy tracks")
        waitText("Copied 3 tracks")
        val clipboard = onMain {
            (Device.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals("First\tLibrary Artist\tLibrary Album\nSecond\tLibrary Artist\tLibrary Album\nThird\tLibrary Artist\tLibrary Album", clipboard)
        clickList("Play")
        awaitPlaying("first")
        assertEquals(listOf("first", "second", "third"), engine.queue.value.entries.map { it.track.id })
        val originalUids = engine.queue.value.entries.map { it.uid }.toSet()
        // Adding a non-adjacent track must insert it next, not append it at the end.
        rowAction("3. Third", "Add to Queue")
        waitText("Added to queue")
        awaitUntil { engine.queue.value.entries.size == 4 }
        val queued = engine.queue.value
        assertEquals(listOf("first", "third", "second", "third"), queued.order.map { queued.entries[it].track.id })
        val insertedUid = queued.entries.single { it.uid !in originalUids }.uid
        assertEquals(insertedUid, queued.upcoming().first().uid)
        tab("NOW_PLAYING")
        reveal(hasContentDescription("Next")).performClick()
        awaitPlaying("third")
        assertEquals(insertedUid, engine.queue.value.current?.uid)
    }

    @Test fun unionCandidatesSaveMaterializeRefreshAndPlay() = runBlocking {
        val a = song("union-a", "Local source", duration = 120_000)
        val b = song("union-b", "Cached source", duration = 120_000)
        val c = song("union-c", "New source", duration = 120_000)
        val standard = seed("Local source list", listOf(a))
        cacheProvider(listOf(b))
        playlists.create("Combined", PlaylistType.UNION)
        openPlaylist("Combined")
        clickList("Edit sources")
        await(hasText("Cached catalog")).performClick()
        await(hasText("Local source list")).performClick()
        text("Save").performClick()
        show("Tracks: 2")
        show("1. Cached catalog (jellyfin)")
        show("2. Local source list (custom)")
        show("1. Cached source")
        show("2. Local source")
        playlists.addTrack(standard.id, c)
        clickList("Refresh")
        show("Union playlist materialized successfully!")
        show("Tracks: 3")
        clickList("Play")
        awaitPlaying("union-b")
        assertEquals(listOf("union-b", "union-a", "union-c"), engine.queue.value.entries.map { it.track.id })
        assertTrue(server.requestsTo("/audio/union-b.wav").isNotEmpty())
    }

    @Test fun nowPlayingTransportTitleStatusAndSeeking() = runBlocking {
        val songs = listOf(song("one", "Transport One", duration = 120_000), song("two", "Transport Two", duration = 120_000))
        queue(songs)
        open()
        show("Transport One")
        show("Library Artist")
        reveal(hasContentDescription("Play")).performClick()
        awaitPlaying("one")
        show("Playing")
        reveal(hasContentDescription("Pause")).performClick()
        awaitUntil { engine.player.value.status == PlaybackStateType.PAUSED }
        show("Paused")
        reveal(hasContentDescription("Playback position")).performSemanticsAction(SemanticsActions.SetProgress) { it(30_000f) }
        awaitUntil { engine.positionMs() in 29_000..31_000 }
        show("0:30")
        reveal(hasContentDescription("Play")).performClick()
        awaitPlaying("one")
        reveal(hasContentDescription("Next")).performClick()
        awaitPlaying("two")
        onMain {
            engine.pause()
            engine.seekTo(0)
        }
        awaitUntil { engine.player.value.status == PlaybackStateType.PAUSED && engine.positionMs() <= 3_000 }
        reveal(hasContentDescription("Previous")).performClick()
        awaitPlaying("one")
    }

    @Test fun volumeShuffleAndRepeatControls(): Unit = runBlocking {
        queue(listOf(song("modes", "Modes", duration = 120_000)))
        open()
        reveal(hasContentDescription("Volume")).performSemanticsAction(SemanticsActions.SetProgress) { it(35f) }
        awaitUntil { engine.player.value.volume == 35 }
        reveal(hasContentDescription("Volume") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo, androidx.compose.ui.semantics.ProgressBarRangeInfo(35f, 0f..100f))).assertExists()
        clickList("Shuffle")
        awaitUntil { engine.queue.value.shuffle }
        reveal(hasText("Shuffle") and isSelected()).assertIsSelected()
        clickList("Shuffle")
        awaitUntil { !engine.queue.value.shuffle }
        for ((label, mode) in listOf("One" to RepeatMode.ONE, "All" to RepeatMode.ALL, "Off" to RepeatMode.OFF)) {
            clickList(label)
            awaitUntil { engine.queue.value.repeat == mode }
            reveal(hasText(label) and isSelected()).assertIsSelected()
        }
        // Volume icons are labels, not buttons: no click action exists in the product UI.
        reveal(hasContentDescription("Volume down")).assertExists()
        compose.onNodeWithContentDescription("Volume up").assertExists()
    }

    @Test fun queueJumpAndHistoryHeaders() = runBlocking {
        queue(listOf(song("q1", "Queue One", duration = 120_000), song("q2", "Queue Two", duration = 120_000), song("q3", "Queue Three", duration = 120_000)))
        open()
        show("Up Next")
        clickList("Queue Two")
        awaitPlaying("q2")
        show("1 track already played")
        clickList("Queue Three")
        awaitPlaying("q3")
        show("2 tracks already played")
        clickList("Queue One")
        awaitPlaying("q1")
        show("Up Next")
        show("Queue Two")
    }

    @Test fun emptyQueueAndUnplayableErrorAreVisible() = runBlocking {
        open()
        show("No queue loaded")
        engine.setQueue(listOf(Track("broken", "Missing audio", "Library Artist", source = SourceType.CUSTOM)), autoPlay = true)
        awaitUntil { engine.player.value.error == "Nothing playable in the queue" }
        show("Error: Nothing playable in the queue")
        show("Error")
    }

    @Test fun skippedTrackErrorClearsOnceReplacementPlays() = runBlocking {
        val missing = Track("missing", "Missing audio", "Library Artist", source = SourceType.CUSTOM)
        queue(listOf(missing, song("after", "Playable after", duration = 120_000)))
        open()
        show("Error: Can't play \"Missing audio\"")
        reveal(hasContentDescription("Play")).performClick()
        awaitPlaying("after")
        show("Playing")
        awaitUntil(message = "skip error cleared while the replacement plays") { engine.player.value.error == null }
        lazyList.performScrollToIndex(0)
        waitAbsent("Error: Can't play \"Missing audio\"")
        assertEquals(PlaybackStateType.PLAYING, engine.player.value.status)
    }

    @Test fun navigationMiniPlayerBackAndTabState() = runBlocking {
        seed("Navigation mix", listOf(song("nav-a", "Nav Alpha", duration = 120_000), song("nav-b", "Nav Beta", duration = 120_000)))
        queue(listOf(song("mini-a", "Mini One", duration = 120_000), song("mini-b", "Mini Two", duration = 120_000)))
        openPlaylist("Navigation mix")
        reveal(hasText("Search tracks")).performTextInput("Alpha")
        hideKeyboard()
        tab("SEARCH")
        text("Search tracks or playlists").assertExists()
        tab("SETTINGS")
        compose.onNodeWithTag("settings_tab_GENERAL").assertExists()
        tab("PLAYLISTS")
        show("1. Nav Alpha")
        waitAbsent("2. Nav Beta")
        await(hasContentDescription("Play")).performClick()
        awaitPlaying("mini-a")
        await(hasContentDescription("Pause")).performClick()
        awaitUntil { engine.player.value.status == PlaybackStateType.PAUSED }
        await(hasContentDescription("Play")).assertExists()
        compose.onNodeWithContentDescription("Next").performClick()
        awaitUntil { engine.queue.value.current?.track?.id == "mini-b" }
        await(hasText("Mini Two")).performClick()
        reveal(hasContentDescription("Playback position")).assertExists()
        tab("PLAYLISTS")
        compose.onNodeWithContentDescription("Back").performClick()
        show("Navigation mix")
    }

    private fun open() { app = ActivityScenario.launch(MainActivity::class.java) }

    private fun tab(name: String) {
        compose.onNodeWithTag("tab_$name").performClick()
        compose.waitForIdle()
    }

    private fun openPlaylist(name: String) {
        open()
        tab("PLAYLISTS")
        clickList(name)
        waitText(name)
    }

    private fun text(value: String) = compose.onNodeWithText(value)

    /** Waits for a node outside the screen's lazy list (dialogs, mini-player, snackbars). */
    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }

    private fun waitText(value: String) { await(hasText(value)) }

    private fun waitAbsent(value: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isEmpty() }
    }

    private fun hideKeyboard() {
        app!!.onActivity { activity ->
            val keyboard = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
    }

    private fun reveal(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(30_000) {
            runCatching { lazyList.performScrollToNode(matcher) }.isSuccess
        }
        return compose.onNode(matcher)
    }

    private fun show(value: String) { reveal(hasText(value)).assertIsDisplayed() }
    private fun clickList(value: String) { reveal(hasText(value)).performClick() }

    private fun create(name: String, union: Boolean) {
        val type = if (union) "union" else "standard"
        reveal(hasTestTag("field_new_${type}_playlist_name")).performTextReplacement(name)
        reveal(hasTestTag("create_${type}_playlist_button")).performClick()
        hideKeyboard()
        show(name.ifBlank { "Untitled Playlist" })
    }

    private fun rowAction(title: String, action: String) {
        reveal(hasText(title))
        val rowY = text(title).fetchSemanticsNode().boundsInRoot.center.y
        val menus = compose.onAllNodesWithContentDescription("Track actions")
        val nearest = menus.fetchSemanticsNodes().indices.minBy { index ->
            kotlin.math.abs(menus[index].fetchSemanticsNode().boundsInRoot.center.y - rowY)
        }
        menus[nearest].performClick()
        text(action).performClick()
    }

    private fun assertRowOrder(first: String, second: String) {
        reveal(hasText(first))
        compose.waitUntil(30_000) {
            val a = text(first).fetchSemanticsNode().positionInRoot.y
            val b = text(second).fetchSemanticsNode().positionInRoot.y
            a < b
        }
    }

    private fun song(id: String, title: String, artist: String = "Library Artist", album: String = "Library Album", duration: Long = 10_000): Track =
        Track(id, title, artist, album, duration, SourceType.CUSTOM, server.audio("/audio/$id.wav", duration))

    private suspend fun seed(name: String, tracks: List<Track> = emptyList()): CustomPlaylist =
        playlists.create(name, PlaylistType.STANDARD).also { playlist -> tracks.forEach { playlists.addTrack(playlist.id, it) } }

    private suspend fun cacheProvider(tracks: List<Track>) {
        val source = SourceType.JELLYFIN
        val key = providerOwnerKey(source, "catalog")
        val now = System.currentTimeMillis()
        database.catalogCacheDao().putPlaylists(listOf(CachedPlaylistEntity(source, "catalog", "Cached catalog", "Fixture", tracks.size, null, null, 0, now)))
        database.catalogCacheDao().replaceTracks(CachedTrackOwnerEntity(key, now, tracks.size), tracks.mapIndexed { index, track -> CachedTrackEntity(key, index, AppJson.encodeToString(track)) })
    }

    private suspend fun queue(tracks: List<Track>) {
        engine.awaitRestored()
        engine.setQueue(tracks, autoPlay = false)
        awaitUntil { engine.queue.value.entries.size == tracks.size }
    }

    private suspend fun awaitPlaying(id: String) {
        awaitUntil(message = "playing $id") { engine.queue.value.current?.track?.id == id && engine.player.value.status == PlaybackStateType.PLAYING }
    }
}
