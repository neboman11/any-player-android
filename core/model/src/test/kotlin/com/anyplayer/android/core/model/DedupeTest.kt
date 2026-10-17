package com.anyplayer.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture-driven parity with the shared cross-platform `dedup_spec.json`. */
class DedupeTest {

    @Serializable
    private data class FixtureRoot(@SerialName("test_cases") val testCases: List<FixtureTestCase>)

    @Serializable
    private data class FixtureTestCase(
        val id: String,
        val description: String,
        val input: List<FixtureTrack>,
        @SerialName("expected_deduped_count") val expectedDedupedCount: Int,
        @SerialName("expected_duplicate_groups") val expectedDuplicateGroups: List<FixtureDuplicateGroup>,
    )

    @Serializable
    private data class FixtureTrack(val id: String, val title: String, val artist: String)

    @Serializable
    private data class FixtureDuplicateGroup(
        val key: String,
        @SerialName("first_occurrence_id") val firstOccurrenceId: String,
        @SerialName("first_occurrence_index") val firstOccurrenceIndex: Int,
        @SerialName("duplicate_ids") val duplicateIds: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadFixture(): FixtureRoot {
        val stream = javaClass.classLoader!!.getResourceAsStream("dedup_spec.json")
        assertNotNull("dedup_spec.json must be present in test resources", stream)
        return json.decodeFromString(stream!!.bufferedReader().use { it.readText() })
    }

    private fun FixtureTrack.toPlaylistTrack(position: Int) = PlaylistTrack(
        id = id,
        playlistId = "test-playlist",
        trackSource = SourceType.CUSTOM,
        trackId = id,
        position = position,
        addedAt = "2026-01-01T00:00:00Z",
        title = title,
        artist = artist,
    )

    @Test
    fun fixtureIsLoadedWithAtLeastOneCase() {
        assertTrue(loadFixture().testCases.isNotEmpty())
    }

    @Test
    fun allFixtureCasesPass_playlistTracks() {
        loadFixture().testCases.forEach { case ->
            val input = case.input.mapIndexed { i, t -> t.toPlaylistTrack(i) }
            val result = Dedupe.deduplicate(input)
            check(case, result.tracks.size, result.duplicateGroups) { input[it].trackId }
        }
    }

    @Test
    fun allFixtureCasesPass_tracks() {
        loadFixture().testCases.forEach { case ->
            val input = case.input.map { Track(id = it.id, title = it.title, artist = it.artist, source = SourceType.JELLYFIN) }
            val result = Dedupe.deduplicateTracks(input)
            check(case, result.tracks.size, result.duplicateGroups) { input[it].id }
        }
    }

    private fun check(case: FixtureTestCase, dedupedCount: Int, groups: List<DuplicateGroup>, idAt: (Int) -> String) {
        val label = "Case '${case.id}' (${case.description})"
        assertEquals("$label: deduped count", case.expectedDedupedCount, dedupedCount)
        assertEquals("$label: group count", case.expectedDuplicateGroups.size, groups.size)
        case.expectedDuplicateGroups.forEach { expected ->
            val actual = groups.find { it.key == expected.key }
            assertNotNull("$label: group '${expected.key}' missing (have ${groups.map { it.key }})", actual)
            actual!!
            assertEquals("$label: firstOccurrenceIndex", expected.firstOccurrenceIndex, actual.firstOccurrenceIndex)
            assertEquals("$label: first id", expected.firstOccurrenceId, idAt(actual.firstOccurrenceIndex))
            assertEquals("$label: duplicate ids", expected.duplicateIds, actual.occurrences.map { it.trackId })
        }
    }

    @Test
    fun keyIsTrimmedLowercaseTitlePipeArtist() {
        assertEquals("hello world|the band", Dedupe.key("  Hello World\t", " THE BAND "))
        assertEquals("instrumental|", Dedupe.key("Instrumental", ""))
    }

    @Test
    fun groupsAreOrderedByFirstOccurrence() {
        val tracks = listOf(
            jf("1", "Song A", "X"), jf("2", "Song B", "Y"), jf("3", "Song B", "Y"), jf("4", "Song A", "X"),
        )
        val result = Dedupe.deduplicateTracks(tracks)
        assertEquals(listOf("1", "2"), result.tracks.map { it.id })
        assertEquals(listOf("song a|x", "song b|y"), result.duplicateGroups.map { it.key })
    }
}
