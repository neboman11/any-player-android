package com.anyplayer.android.data.transfer

import com.anyplayer.android.core.model.*
import java.io.File
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Golden vectors for the desktop (Rust) port. Set `ANYPLAYER_FIXTURE_OUT` (env or system property) to
 * write `state_transfer.json` and `config_ids.json` into that directory; otherwise only self-checked.
 */
class DesktopFixtureGeneratorTransferTest {
    private val time = "2023-11-14T22:13:20Z"
    private val crypto = StateTransferCrypto()
    private val transfer = LegacyStateTransfer(PlaylistImporter(MemoryTransferStore()), MemoryConnections(), "fixture")
    private val pretty = Json { prettyPrint = true }

    private fun track(id: String, source: SourceType, title: String = "Song $id") =
        Track(id = id, title = title, artist = "Artist", source = source, url = if (source == SourceType.SPOTIFY) null else "https://media/$id")

    private fun rich(isDistinct: Boolean) = AnyPlayerStateData(
        customPlaylists = listOf(
            CustomPlaylist("p1", "Café 🎶 東京", description = "q\"uote\\back\nline\u0001", imageUrl = null, createdAt = time, updatedAt = time, trackCount = 2, playlistType = PlaylistType.STANDARD, isDistinct = isDistinct),
            CustomPlaylist("u1", "Union", createdAt = time, updatedAt = "2024-01-02T03:04:05Z", trackCount = 0, playlistType = PlaylistType.UNION, imageUrl = "https://img/u1.png"),
        ),
        playlistTracks = listOf(
            PlaylistTrack("row-1", "p1", SourceType.SPOTIFY, "4uLU6hMCjMI75M1A2tKUQC", 0, time, "Ünïcödé", "😀 Artist", album = null, durationMs = 213000),
            PlaylistTrack("row-2", "p1", SourceType.CUSTOM, "c1", 1, time, "Custom", "Me", album = "Alb", imageUrl = "https://img/c1", url = "https://media/c1.mp3"),
        ),
        unionPlaylistSources = listOf(
            UnionPlaylistSource("us-1", "u1", SourceType.JELLYFIN, "jf-pl", 0, time),
            UnionPlaylistSource("us-2", "u1", SourceType.CUSTOM, "p1", 1, time),
        ),
        columnPreferences = mapOf("title" to ColumnPreferences("title", true, 0), "album" to ColumnPreferences("album", false, 3)),
        connections = mapOf(
            SourceType.SPOTIFY to ExportedConnection(SourceType.SPOTIFY, spotifyConnected = true, spotifyPremium = false),
            SourceType.JELLYFIN to ExportedConnection(SourceType.JELLYFIN, serverUrl = "https://jf.example", username = "ü", token = "jf-token", apiKey = "jf-key"),
            SourceType.PLEX to ExportedConnection(SourceType.PLEX, serverUrl = "http://plex:32400", token = "plex-token"),
            SourceType.CUSTOM to ExportedConnection(SourceType.CUSTOM),
        ),
        playbackState = PlaybackStatus(
            state = PlaybackStateType.ERROR, shuffle = true, repeatMode = RepeatMode.ALL, volume = 42,
            currentTrack = track("4uLU6hMCjMI75M1A2tKUQC", SourceType.SPOTIFY), position = 1234, duration = 213000,
            queue = listOf(track("4uLU6hMCjMI75M1A2tKUQC", SourceType.SPOTIFY), track("c1", SourceType.CUSTOM, "東京")),
            orderedQueue = listOf(track("c1", SourceType.CUSTOM, "東京"), track("4uLU6hMCjMI75M1A2tKUQC", SourceType.SPOTIFY)),
            errorMessage = "Playback failed: \"quoted\"",
        ),
    )

    /** Exactly as [LegacyStateTransfer.export] builds the envelope. */
    private fun envelope(data: AnyPlayerStateData, appVersion: String = "1.2.3"): Pair<AnyPlayerStateEnvelope, String> {
        val seed = AnyPlayerStateEnvelope(createdAt = time, sourceApp = SourceAppInfo("android", appVersion), data = data, integrity = Integrity(""))
        val integrityInput = AppJson.encodeToString(seed)
        return seed.copy(integrity = Integrity(crypto.sha256(integrityInput))) to integrityInput
    }

    @Test fun `state transfer and config id vectors`() {
        val cases = listOf(
            Triple("empty", AnyPlayerStateData(), null),
            Triple("rich_distinct", rich(true), null),
            Triple("rich_not_distinct", rich(false), null),
            Triple("playback_minimal", AnyPlayerStateData(playbackState = PlaybackStatus(PlaybackStateType.IDLE, false, RepeatMode.OFF, 100, position = 0, duration = 0, queue = emptyList())), null),
            Triple("private_rich", rich(true), "pässwörd 🔑"),
            Triple("private_empty", AnyPlayerStateData(), "secret"),
        ).map { (name, data, passphrase) ->
            val (env, integrityInput) = envelope(data)
            val json = AppJson.encodeToString(env)
            val encrypted = passphrase?.let { AppJson.encodeToString(crypto.encrypt(json, it, iterations = 1000)) }
            // Real import path accepts the bytes and recovers the same envelope.
            assertEquals(env, transfer.decodeAndVerify(encrypted ?: json, passphrase))
            assertEquals(AppJson.encodeToString(env.copy(integrity = Integrity(""))), integrityInput)
            buildJsonObject {
                put("name", name); put("envelope_json", json); put("integrity_input", integrityInput)
                put("sha256", env.integrity.sha256); put("passphrase", passphrase); put("encrypted_file", encrypted)
            }
        }

        val keys = listOf("track:pl1:SPOTIFY:abc", "track:pl1:SPOTIFY:abc:occurrence:1", "track:pl1:SPOTIFY:abc:occurrence:2",
            "union:u1:CUSTOM:p2", "union:u1:CUSTOM:p2:occurrence:1", "track:Café 🎶:JELLYFIN:東京", "", "track:p:PLEX:a:b")
        val nameUuid = keys.map { key ->
            buildJsonObject { put("key", key); put("uuid", UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()) }
        }
        assertEquals("3", UUID.fromString(nameUuid[0]["uuid"]!!.jsonPrimitive.content).version().toString())

        // Same configuration as LegacyConfigFiles.syncJson (private): explicit nulls, defaults omitted.
        val syncJson = Json { ignoreUnknownKeys = true }
        val list = ListSerializer(ConfigCustomPlaylist.serializer())
        val exports = listOf(
            "empty" to emptyList(),
            "standard_with_nulls" to listOf(ConfigCustomPlaylist(
                ConfigPlaylistEntry("p1", "Café 🎶", null, null, 1700000000, 1700000001, 2, "standard", false),
                listOf(ConfigTrack(1, "p1", "spotify", "abc", 0, 1700000000, "T\"q", "Ä", null, null, null),
                    ConfigTrack(2, "p1", "custom", "c1", 1, 1700000002, "東京", "B", "Alb", 1234, "https://img")))),
            "union_distinct" to listOf(ConfigCustomPlaylist(
                ConfigPlaylistEntry("u1", "Union", "desc\n", "https://img/u", 1, 2, 0, "union", true),
                unionSources = listOf(ConfigUnionSource(1, "u1", "jellyfin", "jf", 0, 5), ConfigUnionSource(2, "u1", "custom", "p1", 1, 6)))),
        ).map { (name, entries) ->
            val json = syncJson.encodeToString(list, entries)
            assertEquals(entries, syncJson.decodeFromString(list, json))
            buildJsonObject { put("name", name); put("json", json) }
        }

        fixtureOut()?.let { dir ->
            File(dir, "state_transfer.json").writeText(pretty.encodeToString(JsonElement.serializer(), buildJsonObject { put("cases", JsonArray(cases)) }) + "\n")
            File(dir, "config_ids.json").writeText(pretty.encodeToString(JsonElement.serializer(), buildJsonObject {
                put("name_uuid", JsonArray(nameUuid)); put("export_json", JsonArray(exports))
            }) + "\n")
        }
    }

    private fun fixtureOut(): File? = (System.getProperty("ANYPLAYER_FIXTURE_OUT") ?: System.getenv("ANYPLAYER_FIXTURE_OUT"))
        ?.takeIf { it.isNotBlank() }?.let(::File)?.also { it.mkdirs() }
}
