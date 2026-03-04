package com.anyplayer.android.feature.playlists

import com.anyplayer.android.core.model.PlaylistTrack
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture-driven regression tests for [DistinctPlaylistUtils.deduplicate].
 *
 * Cases are loaded from `dedup_spec.json` (in test resources) which is the shared
 * cross-platform spec copied byte-identical from `test-fixtures/dedup_spec.json`.
 * Any Kotlin/Rust behavioral drift is caught here automatically.
 */
class DistinctPlaylistUtilsTest {

    // ── Fixture schema ──────────────────────────────────────────────────────

    @Serializable
    private data class FixtureRoot(
        @SerialName("test_cases") val testCases: List<FixtureTestCase>
    )

    @Serializable
    private data class FixtureTestCase(
        val id: String,
        val description: String,
        val input: List<FixtureTrack>,
        @SerialName("expected_deduped_count") val expectedDedupedCount: Int,
        @SerialName("expected_duplicate_groups") val expectedDuplicateGroups: List<FixtureDuplicateGroup>
    )

    @Serializable
    private data class FixtureTrack(
        val id: String,
        val title: String,
        val artist: String
    )

    @Serializable
    private data class FixtureDuplicateGroup(
        val key: String,
        @SerialName("first_occurrence_id") val firstOccurrenceId: String,
        @SerialName("first_occurrence_index") val firstOccurrenceIndex: Int,
        @SerialName("duplicate_ids") val duplicateIds: List<String>
    )

    // ── Helpers ─────────────────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadFixture(): FixtureRoot {
        val stream = javaClass.classLoader!!.getResourceAsStream("dedup_spec.json")
        assertNotNull(
            "dedup_spec.json must be present in test resources (classLoader returned null)",
            stream
        )
        val text = stream!!.bufferedReader().readText()
        return json.decodeFromString(text)
    }

    /**
     * Converts a fixture track (id + title + artist) to a [PlaylistTrack] using stub
     * values for fields not covered by the dedup spec.
     */
    private fun FixtureTrack.toPlaylistTrack(position: Int): PlaylistTrack = PlaylistTrack(
        id = id,
        playlistId = "test-playlist",
        trackSource = SourceType.CUSTOM,
        trackId = id,
        position = position,
        addedAt = "2026-01-01T00:00:00Z",
        title = title,
        artist = artist
    )

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun fixtureIsLoadedWithAtLeastOneCase() {
        val fixture = loadFixture()
        assertTrue(
            "dedup_spec.json must contain at least one test case",
            fixture.testCases.isNotEmpty()
        )
    }

    @Test
    fun allFixtureCasesPass() {
        val fixture = loadFixture()

        fixture.testCases.forEach { testCase ->
            val inputTracks = testCase.input.mapIndexed { index, track ->
                track.toPlaylistTrack(index)
            }
            val result = DistinctPlaylistUtils.deduplicate(inputTracks)

            // Deduped count
            assertEquals(
                "Case '${testCase.id}' (${testCase.description}): deduped count mismatch",
                testCase.expectedDedupedCount,
                result.tracks.size
            )

            // Duplicate group count
            assertEquals(
                "Case '${testCase.id}' (${testCase.description}): duplicate group count mismatch",
                testCase.expectedDuplicateGroups.size,
                result.duplicateGroups.size
            )

            // Per-group assertions
            testCase.expectedDuplicateGroups.forEach { expectedGroup ->
                val actualGroup = result.duplicateGroups.find { it.key == expectedGroup.key }
                assertNotNull(
                    "Case '${testCase.id}': expected duplicate group with key '${expectedGroup.key}' not found " +
                        "(actual keys: ${result.duplicateGroups.map { it.key }})",
                    actualGroup
                )
                requireNotNull(actualGroup)

                // First occurrence index in original input
                assertEquals(
                    "Case '${testCase.id}' key='${expectedGroup.key}': firstOccurrenceIndex mismatch",
                    expectedGroup.firstOccurrenceIndex,
                    actualGroup.firstOccurrenceIndex
                )

                // First occurrence track id matches input slot
                assertEquals(
                    "Case '${testCase.id}' key='${expectedGroup.key}': first occurrence id mismatch",
                    expectedGroup.firstOccurrenceId,
                    inputTracks[actualGroup.firstOccurrenceIndex].trackId
                )

                // Duplicate track ids (in input order — occurrences are appended in order)
                assertEquals(
                    "Case '${testCase.id}' key='${expectedGroup.key}': duplicate ids mismatch",
                    expectedGroup.duplicateIds,
                    actualGroup.occurrences.map { it.trackId }
                )
            }
        }
    }

    // ── deduplicateTracks (Track overload) ───────────────────────────────────

    private fun track(id: String, title: String, artist: String): Track = Track(
        id = id,
        title = title,
        artist = artist,
        source = SourceType.SPOTIFY,
        durationMs = 180_000L,
        enriched = true
    )

    @Test
    fun deduplicateTracks_noDuplicates_returnsAllTracks() {
        val tracks = listOf(
            track("1", "Alpha", "Artist A"),
            track("2", "Beta", "Artist B"),
            track("3", "Gamma", "Artist C")
        )
        val result = DistinctPlaylistUtils.deduplicateTracks(tracks)

        assertEquals(3, result.tracks.size)
        assertTrue(result.duplicateGroups.isEmpty())
        assertEquals(listOf("1", "2", "3"), result.tracks.map { it.id })
    }

    @Test
    fun deduplicateTracks_exactDuplicates_keepsFirstOccurrence() {
        val tracks = listOf(
            track("1", "Alpha", "Artist A"),
            track("2", "Beta", "Artist B"),
            track("3", "Alpha", "Artist A") // duplicate of track 1
        )
        val result = DistinctPlaylistUtils.deduplicateTracks(tracks)

        assertEquals(2, result.tracks.size)
        assertEquals(listOf("1", "2"), result.tracks.map { it.id })
        assertEquals(1, result.duplicateGroups.size)

        val group = result.duplicateGroups.first()
        assertEquals("alpha|artist a", group.key)
        assertEquals(0, group.firstOccurrenceIndex)
        assertEquals(listOf("3"), group.occurrences.map { it.trackId })
    }

    @Test
    fun deduplicateTracks_caseInsensitiveAndTrimmed_treatedAsDuplicate() {
        val tracks = listOf(
            track("1", "  Hello World  ", "  The Band  "),
            track("2", "Hello World", "The Band"),       // same after trim+lowercase
            track("3", "HELLO WORLD", "THE BAND")        // same after trim+lowercase
        )
        val result = DistinctPlaylistUtils.deduplicateTracks(tracks)

        assertEquals(1, result.tracks.size)
        assertEquals("1", result.tracks.first().id)
        assertEquals(1, result.duplicateGroups.size)

        val group = result.duplicateGroups.first()
        assertEquals("hello world|the band", group.key)
        assertEquals(0, group.firstOccurrenceIndex)
        assertEquals(listOf("2", "3"), group.occurrences.map { it.trackId })
    }

    @Test
    fun deduplicateTracks_emptyArtist_usesEmptyStringInKey() {
        val tracks = listOf(
            track("1", "Instrumental", ""),
            track("2", "Instrumental", "")  // same title, empty artist
        )
        val result = DistinctPlaylistUtils.deduplicateTracks(tracks)

        assertEquals(1, result.tracks.size)
        assertEquals("1", result.tracks.first().id)
        assertEquals(1, result.duplicateGroups.size)
        assertEquals("instrumental|", result.duplicateGroups.first().key)
    }

    @Test
    fun deduplicateTracks_emptyList_returnsEmpty() {
        val result = DistinctPlaylistUtils.deduplicateTracks(emptyList())

        assertTrue(result.tracks.isEmpty())
        assertTrue(result.duplicateGroups.isEmpty())
    }

    @Test
    fun deduplicateTracks_multipleGroups_allDetected() {
        val tracks = listOf(
            track("1", "Song A", "Artist X"),
            track("2", "Song B", "Artist Y"),
            track("3", "Song A", "Artist X"), // dup of 1
            track("4", "Song B", "Artist Y"), // dup of 2
            track("5", "Song C", "Artist Z"),
            track("6", "Song A", "Artist X")  // second dup of 1
        )
        val result = DistinctPlaylistUtils.deduplicateTracks(tracks)

        assertEquals(3, result.tracks.size)
        assertEquals(listOf("1", "2", "5"), result.tracks.map { it.id })
        assertEquals(2, result.duplicateGroups.size)

        val groupA = result.duplicateGroups.find { it.key == "song a|artist x" }
        assertNotNull(groupA)
        assertEquals(0, groupA!!.firstOccurrenceIndex)
        assertEquals(listOf("3", "6"), groupA.occurrences.map { it.trackId })

        val groupB = result.duplicateGroups.find { it.key == "song b|artist y" }
        assertNotNull(groupB)
        assertEquals(1, groupB!!.firstOccurrenceIndex)
        assertEquals(listOf("4"), groupB.occurrences.map { it.trackId })
    }
}
