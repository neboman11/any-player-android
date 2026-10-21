package com.anyplayer.android.core.model

import java.io.File
import kotlin.random.Random
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Golden vectors for the desktop (Rust) port. Set `ANYPLAYER_FIXTURE_OUT` (env or system property) to
 * write `volume_normalization.json` and `break_schedule.json` into that directory; otherwise only self-checked.
 */
class DesktopFixtureGeneratorModelTest {
    private val pretty = Json { prettyPrint = true }

    @Test fun `volume normalization vectors`() {
        fun table(source: SourceType, enabled: Boolean) = (0..100).map { VolumeNormalization.effective(it, source, enabled) }
        val root = buildJsonObject {
            for (source in listOf(SourceType.SPOTIFY, SourceType.JELLYFIN, SourceType.PLEX, SourceType.CUSTOM)) {
                put(source.name.lowercase(), JsonArray(table(source, true).map(::JsonPrimitive)))
            }
            put("disabled", JsonArray(table(SourceType.SPOTIFY, false).map(::JsonPrimitive)))
        }
        assertEquals((0..100).toList(), table(SourceType.PLEX, false))
        assertEquals(85, VolumeNormalization.effective(100, SourceType.SPOTIFY, true))
        fixtureOut()?.let { File(it, "volume_normalization.json").writeText(pretty.encodeToString(JsonElement.serializer(), root) + "\n") }
    }

    /** Returns scripted values for the reducer's only random draw, `nextInt(3, 6)`. */
    private class ScriptedRandom(values: List<Int>) : Random() {
        private val queue = ArrayDeque(values)
        val used = mutableListOf<Int>()
        override fun nextBits(bitCount: Int): Int = error("unscripted draw")
        override fun nextInt(from: Int, until: Int): Int {
            val v = queue.removeFirst()
            require(v in from until until) { "scripted $v outside [$from, $until)" }
            return v.also(used::add)
        }
    }

    private sealed interface Step { val queue: QueueState }
    private data class Ev(val event: ScheduleEvent, override val queue: QueueState) : Step

    private fun t(i: Int, filler: Boolean = false, playable: Boolean = true) = Track(
        id = "t$i", title = "T$i", artist = "A", source = SourceType.CUSTOM,
        url = if (playable) "file:///t$i.wav" else null, isDjFiller = filler)

    /** Identity order, uids 1..n, matching [QueueOps.replace] with shuffle off. */
    private fun q(tracks: List<Track>, cursor: Int, version: Long = 1) = QueueState(
        entries = tracks.mapIndexed { i, tr -> QueueEntry(i + 1L, tr) }, order = tracks.indices.toList(),
        cursor = cursor, contentVersion = version, nextUid = tracks.size + 1L)

    private fun eventName(e: ScheduleEvent) = when (e) {
        is ScheduleEvent.Enabled -> if (e.enabled) "enable" else "disable"
        ScheduleEvent.QueueReplaced -> "queue_replaced"
        ScheduleEvent.CursorMoved -> "cursor_moved"
        ScheduleEvent.BreakStarted -> "break_started"
        ScheduleEvent.GenerationFailed -> "generation_failed"
    }

    private fun queueJson(q: QueueState) = buildJsonObject {
        put("entries", JsonArray(q.entries.map { e ->
            buildJsonObject {
                put("uid", e.uid); put("track", AppJson.encodeToJsonElement(Track.serializer(), e.track))
            }
        }))
        put("order", JsonArray(q.order.map(::JsonPrimitive)))
        put("cursor", q.cursor); put("content_version", q.contentVersion)
    }

    @Test fun `break schedule vectors`() {
        val plain = (0 until 12).map { t(it) }
        val mixed = plain.mapIndexed { i, tr -> if (i == 3 || i == 6) t(i, filler = true) else if (i == 5) t(i, playable = false) else tr }
        val unplayableTail = (0 until 6).map { t(it, playable = it < 2) }
        val cases: List<Triple<String, List<Int>, List<Step>>> = listOf(
            Triple("enable_then_advance", listOf(3, 4), listOf(
                Ev(ScheduleEvent.Enabled(true), q(plain, 0)), Ev(ScheduleEvent.CursorMoved, q(plain, 1)),
                Ev(ScheduleEvent.CursorMoved, q(plain, 2)), Ev(ScheduleEvent.CursorMoved, q(plain, 3)),
                Ev(ScheduleEvent.CursorMoved, q(plain, 4)), Ev(ScheduleEvent.BreakStarted, q(plain, 4)))),
            Triple("intervals_4_and_5", listOf(4, 5), listOf(
                Ev(ScheduleEvent.Enabled(true), q(plain, 0)), Ev(ScheduleEvent.CursorMoved, q(plain, 2)),
                Ev(ScheduleEvent.QueueReplaced, q(plain, 0, version = 2)), Ev(ScheduleEvent.CursorMoved, q(plain, 1, version = 2)))),
            Triple("fillers_and_unplayable", listOf(3), listOf(
                Ev(ScheduleEvent.Enabled(true), q(mixed, 0)), Ev(ScheduleEvent.CursorMoved, q(mixed, 2)),
                Ev(ScheduleEvent.CursorMoved, q(mixed, 4)))),
            Triple("backward_jump_and_edit_reanchor", listOf(5), listOf(
                Ev(ScheduleEvent.Enabled(true), q(plain, 3)), Ev(ScheduleEvent.CursorMoved, q(plain, 1)),
                Ev(ScheduleEvent.CursorMoved, q(plain, 2, version = 7)))),
            Triple("overshoot_and_generation_failed", listOf(3), listOf(
                Ev(ScheduleEvent.Enabled(true), q(plain, 0)), Ev(ScheduleEvent.CursorMoved, q(plain, 9)),
                Ev(ScheduleEvent.GenerationFailed, q(plain, 9)), Ev(ScheduleEvent.CursorMoved, q(plain, 10)))),
            Triple("no_playable_target", listOf(3), listOf(
                Ev(ScheduleEvent.Enabled(true), q(unplayableTail, 0)), Ev(ScheduleEvent.CursorMoved, q(unplayableTail, 1)))),
            Triple("disable_and_reenable", listOf(4, 3), listOf(
                Ev(ScheduleEvent.Enabled(true), q(plain, 0)), Ev(ScheduleEvent.Enabled(true), q(plain, 0)),
                Ev(ScheduleEvent.Enabled(false), q(plain, 1)), Ev(ScheduleEvent.CursorMoved, q(plain, 2)),
                Ev(ScheduleEvent.Enabled(true), q(plain, 2)))),
            Triple("empty_queue", listOf(3), listOf(Ev(ScheduleEvent.Enabled(true), QueueState()))),
        )
        val out = cases.map { (name, intervals, steps) ->
            val random = ScriptedRandom(intervals)
            var s = BreakSchedule()
            val expected = steps.map { step ->
                step as Ev
                s = reduce(s, step.event, step.queue, random)
                buildJsonObject {
                    put("songs_away", s.songsAway(step.queue)); put("target_uid", s.targetUid)
                    put("songs_until_break", s.songsUntilBreak); put("anchor_order_pos", s.anchorOrderPos)
                    put("order_version", s.orderVersion); put("failed_on_current", s.failedOnCurrent); put("enabled", s.enabled)
                }
            }
            assertEquals("$name: every scripted interval drawn", intervals, random.used)
            buildJsonObject {
                put("name", name)
                put("intervals", JsonArray(intervals.map(::JsonPrimitive)))
                put("events", JsonArray(steps.map { step ->
                    step as Ev
                    buildJsonObject { put("event", eventName(step.event)); put("queue", queueJson(step.queue)) }
                }))
                put("expected", JsonArray(expected))
            }
        }
        fixtureOut()?.let { File(it, "break_schedule.json").writeText(pretty.encodeToString(JsonElement.serializer(), buildJsonObject { put("cases", JsonArray(out)) }) + "\n") }
    }

    private fun fixtureOut(): File? = (System.getProperty("ANYPLAYER_FIXTURE_OUT") ?: System.getenv("ANYPLAYER_FIXTURE_OUT"))
        ?.takeIf { it.isNotBlank() }?.let(::File)?.also { it.mkdirs() }
}
