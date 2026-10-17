package com.anyplayer.android.data.transfer

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class MemoryTransferStore(var data: AnyPlayerStateData = AnyPlayerStateData()) : PlaylistTransferStore {
    var writes = 0
    override suspend fun <T> transaction(block: suspend () -> T): T = block()
    override suspend fun snapshot() = data
    override suspend fun apply(data: AnyPlayerStateData, replaceAll: Boolean) {
        writes++
        val ids = data.customPlaylists.map { it.id }.toSet()
        val old = if (replaceAll) AnyPlayerStateData() else this.data
        this.data = data.copy(
            customPlaylists = old.customPlaylists.filter { it.id !in ids } + data.customPlaylists,
            playlistTracks = old.playlistTracks.filter { it.playlistId !in ids } + data.playlistTracks,
            unionPlaylistSources = old.unionPlaylistSources.filter { it.unionPlaylistId !in ids } + data.unionPlaylistSources,
            columnPreferences = old.columnPreferences + data.columnPreferences)
    }
}
internal class MemoryConnections(initial: Map<SourceType, StoredConnection> = emptyMap()) : ConnectionStore {
    override val connections = MutableStateFlow(initial)
    var writes = 0
    override suspend fun awaitLoaded() = connections.value
    override suspend fun update(source: SourceType, transform: (StoredConnection?) -> StoredConnection?) {
        writes++
        val value = transform(connections.value[source])
        connections.value = if (value == null) connections.value - source else connections.value + (source to value)
    }
}

class TransferTest {
    private val time = "2023-11-14T22:13:20Z"
    private fun playlist(name: String = "Local", id: String = "p", type: PlaylistType = PlaylistType.STANDARD) = CustomPlaylist(id, name, createdAt = time, updatedAt = time, trackCount = 1, playlistType = type)
    private fun track() = PlaylistTrack("row", "p", SourceType.PLEX, "song", 0, time, "Title", "Artist", url = "https://media/song")
    private fun data() = AnyPlayerStateData(listOf(playlist(), playlist("Empty Union", "u", PlaylistType.UNION)), listOf(track()), columnPreferences = mapOf("title" to ColumnPreferences("title", true, 2)))
    private fun signed(data: AnyPlayerStateData): String {
        val seed = AnyPlayerStateEnvelope(createdAt = time, sourceApp = SourceAppInfo("android", "test"), data = data, integrity = Integrity(""))
        return AppJson.encodeToString(seed.copy(integrity = Integrity(StateTransferCrypto().sha256(AppJson.encodeToString(seed)))))
    }
    private fun fixture() = requireNotNull(javaClass.getResource("/legacy-state.json")).readText()
    private fun config(vararg entries: ConfigCustomPlaylist, providers: ConfigProviderConfigs = ConfigProviderConfigs()) = Json.encodeToJsonElement(ConfigFile(1, providers, entries.toList()))
    private fun entry(type: String = "standard", tracks: List<ConfigTrack> = emptyList(), sources: List<ConfigUnionSource> = emptyList()) = ConfigCustomPlaylist(ConfigPlaylistEntry("p", "Incoming", createdAt = 1700000000, updatedAt = 1700000000, trackCount = tracks.size, playlistType = type), tracks, sources)

    @Test fun statePortableRoundTripPreservesTracksColumnsAndEmptyUnion() = runTest {
        val source = MemoryTransferStore(data())
        val target = MemoryTransferStore()
        val output = ByteArrayOutputStream()
        LegacyStateTransfer(PlaylistImporter(source), MemoryConnections()).export(output, ExportMode.PORTABLE, null, null)
        val summary = LegacyStateTransfer(PlaylistImporter(target), MemoryConnections()).import(output.toByteArray().inputStream(), MergePolicy.REPLACE_ALL, null, false)
        assertEquals(2, summary.playlistsAdded)
        assertEquals(source.data, target.data)
    }

    @Test fun privateRoundTripPreservesSecretsWithoutClearingRefreshData() = runTest {
        val original = StoredConnection(SourceType.JELLYFIN, "https://server", token = "exported")
        val output = ByteArrayOutputStream()
        LegacyStateTransfer(PlaylistImporter(MemoryTransferStore(data())), MemoryConnections(mapOf(original.source to original))).export(output, ExportMode.PRIVATE, "secret", null)
        val existing = original.copy(token = "old", refreshToken = "refresh", tokenExpiresAt = 123, playlistPageSize = 700, jellyfinUserId = "user")
        val target = MemoryConnections(mapOf(existing.source to existing))
        LegacyStateTransfer(PlaylistImporter(MemoryTransferStore()), target).import(output.toByteArray().inputStream(), MergePolicy.REPLACE_ALL, "secret", false)
        assertEquals(existing.copy(token = "exported", playbackReady = true), target.connections.value[existing.source])
        val root = AppJson.parseToJsonElement(output.toString("UTF-8")).jsonObject
        assertFalse(root.containsKey("encrypted"))
        assertEquals(16, java.util.Base64.getDecoder().decode(root.getValue("saltBase64").jsonPrimitive.content).size)
        assertEquals(12, java.util.Base64.getDecoder().decode(root.getValue("nonceBase64").jsonPrimitive.content).size)
    }

    @Test fun legacyFixtureIntegrityAndOmittedCryptoDefaultsImport() = runTest {
        // Ported seed from legacy StateTransferManagerTest.buildEnvelope (2026-05-18).
        val transfer = LegacyStateTransfer(PlaylistImporter(MemoryTransferStore()), MemoryConnections())
        assertEquals(ImportSummary(), transfer.import(fixture().byteInputStream(), MergePolicy.MERGE_PREFER_IMPORT, null, true))
        val encrypted = StateTransferCrypto().encrypt(fixture(), "secret")
        val json = Json { prettyPrint = true; explicitNulls = false }
        val raw = json.encodeToString(encrypted)
        assertFalse(raw.contains("\"encrypted\""))
        assertEquals(ImportSummary(), transfer.import(raw.byteInputStream(), MergePolicy.MERGE_PREFER_IMPORT, "secret", true))
    }

    @Test fun explicitEncryptedFlagImportsAndWrongPassphraseFails() = runTest {
        val transfer = LegacyStateTransfer(PlaylistImporter(MemoryTransferStore()), MemoryConnections())
        val json = Json { encodeDefaults = true }
        val raw = json.encodeToString(StateTransferCrypto().encrypt(fixture(), "secret"))
        assertEquals(ImportSummary(), transfer.import(raw.byteInputStream(), MergePolicy.REPLACE_ALL, "secret", true))
        try { transfer.import(raw.byteInputStream(), MergePolicy.REPLACE_ALL, "wrong", true); fail("Expected GCM authentication failure") }
        catch (_: javax.crypto.AEADBadTagException) { }
    }

    @Test fun integrityTamperingFailsBeforeAnyWrite() = runTest {
        val store = MemoryTransferStore(data())
        val connections = MemoryConnections()
        val transfer = LegacyStateTransfer(PlaylistImporter(store), connections)
        try { transfer.import(fixture().replace("2026-05-18", "2026-05-19").byteInputStream(), MergePolicy.REPLACE_ALL, null, false); fail("Expected integrity failure") }
        catch (error: IllegalArgumentException) { assertEquals("Integrity check failed", error.message) }
        assertEquals(0, store.writes)
        assertEquals(0, connections.writes)
    }

    @Test fun dryRunWritesNeitherConnectionsNorPlaylists() = runTest {
        val store = MemoryTransferStore(data())
        val connections = MemoryConnections(mapOf(SourceType.PLEX to StoredConnection(SourceType.PLEX, "local", token = "token")))
        val before = store.data
        val beforeConnections = connections.connections.value
        val raw = signed(data().copy(connections = mapOf(SourceType.PLEX to ExportedConnection(SourceType.PLEX, "incoming", token = "incoming"))))
        val summary = LegacyStateTransfer(PlaylistImporter(store), connections).import(raw.byteInputStream(), MergePolicy.REPLACE_ALL, "pass", true)
        assertEquals(1, summary.connectionsImported)
        assertEquals(before, store.data)
        assertEquals(beforeConnections, connections.connections.value)
        assertEquals(0, store.writes)
        assertEquals(0, connections.writes)
    }

    @Test fun portableImportNeverLogsOutExistingProviders() = runTest {
        val local = StoredConnection(SourceType.SPOTIFY, token = "access", refreshToken = "refresh", tokenExpiresAt = 123, spotifyPremium = true, playbackReady = true)
        val connections = MemoryConnections(mapOf(local.source to local))
        val raw = signed(AnyPlayerStateData(connections = mapOf(local.source to ExportedConnection(local.source, spotifyConnected = true))))
        LegacyStateTransfer(PlaylistImporter(MemoryTransferStore()), connections).import(raw.byteInputStream(), MergePolicy.MERGE_KEEP_LOCAL, null, false)
        assertEquals(local, connections.connections.value[local.source])
    }

    @Test fun spotifyRawTokenWarningDoesNotReplaceToken() = runTest {
        val local = StoredConnection(SourceType.SPOTIFY, token = "local", refreshToken = "refresh")
        val connections = MemoryConnections(mapOf(local.source to local))
        val raw = signed(AnyPlayerStateData(connections = mapOf(local.source to ExportedConnection(local.source, token = "raw", spotifyConnected = true))))
        val summary = LegacyStateTransfer(PlaylistImporter(MemoryTransferStore()), connections).import(raw.byteInputStream(), MergePolicy.MERGE_PREFER_IMPORT, "pass", false)
        assertEquals(listOf("Spotify raw access token ignored by policy"), summary.warnings)
        assertEquals("local", connections.connections.value[local.source]?.token)
        assertEquals("refresh", connections.connections.value[local.source]?.refreshToken)
    }

    @Test fun keepLocalDoesNotStealTracksAndRepeatedImportIsStable() = runTest {
        val store = MemoryTransferStore(AnyPlayerStateData(listOf(playlist()), listOf(track())))
        val importer = PlaylistImporter(store)
        val incoming = AnyPlayerStateData(listOf(playlist("Incoming")), listOf(track().copy(title = "Imported")))
        importer.import(incoming, MergePolicy.MERGE_KEEP_LOCAL, false, "import")
        val first = store.data
        importer.import(incoming, MergePolicy.MERGE_KEEP_LOCAL, false, "import")
        assertEquals(first, store.data)
        assertEquals(track(), store.data.playlistTracks.first { it.playlistId == "p" })
        assertEquals(2, store.data.customPlaylists.size)
        assertEquals(2, store.data.playlistTracks.size)
    }

    @Test fun replaceAllRemovesLocalOnlyWhilePreferImportKeepsIt() = runTest {
        for (policy in listOf(MergePolicy.REPLACE_ALL, MergePolicy.MERGE_PREFER_IMPORT)) {
            val store = MemoryTransferStore(AnyPlayerStateData(listOf(playlist(), playlist(id = "only-local")), listOf(track())))
            PlaylistImporter(store).import(AnyPlayerStateData(listOf(playlist("Incoming")), emptyList()), policy, false, "import")
            assertEquals("Incoming", store.data.customPlaylists.first { it.id == "p" }.name)
            assertTrue(store.data.playlistTracks.isEmpty())
            assertEquals(policy == MergePolicy.MERGE_PREFER_IMPORT, store.data.customPlaylists.any { it.id == "only-local" })
        }
    }

    @Test fun configWarningsMatchLegacyAndDryRunIsPure() = runTest {
        val store = MemoryTransferStore()
        val connections = MemoryConnections()
        val badTrack = ConfigTrack(1, "p", "mystery", "song", 0, 1700000000, "Title", "Artist")
        val badSource = ConfigUnionSource(1, "p", "mystery", "source", 0, 1700000000)
        val config = config(entry("mystery", listOf(badTrack), listOf(badSource)), providers = ConfigProviderConfigs(ConfigSpotify("client", "redirect"), ConfigJellyfin("https://server")))
        val summary = LegacyConfigFiles(PlaylistImporter(store), connections).importJson(config, MergePolicy.REPLACE_ALL, true)
        assertEquals(listOf("Unknown playlist_type 'mystery' for playlist 'p'; defaulted to 'standard'", "Unknown track_source 'mystery' for track 'song'; skipped", "Unknown source_type 'mystery' for union source 'source'; skipped", "Spotify client_id/redirect_uri in config are app build settings — skipped"), summary.warnings)
        assertEquals(1, summary.connectionsImported)
        assertEquals(0, store.writes)
        assertEquals(0, connections.writes)
    }

    @Test fun configPreservesTokensAndNaturalKeysAndDuplicateTracks() = runTest {
        val store = MemoryTransferStore()
        val local = StoredConnection(SourceType.JELLYFIN, "old", token = "keep", refreshToken = "keep-refresh")
        val connections = MemoryConnections(mapOf(local.source to local))
        val track = ConfigTrack(99, "p", " PlEx ", "song", 0, 1700000000, "Title", "Artist")
        val config = config(entry(tracks = listOf(track, track.copy(position = 1))), providers = ConfigProviderConfigs(jellyfin = ConfigJellyfin("new")))
        val files = LegacyConfigFiles(PlaylistImporter(store), connections)
        files.importJson(config, MergePolicy.REPLACE_ALL, false)
        assertEquals(local.copy(serverUrl = "new"), connections.connections.value[local.source])
        assertEquals(UUID.nameUUIDFromBytes("track:p:PLEX:song".toByteArray()).toString(), store.data.playlistTracks.first().id)
        assertEquals(2, store.data.playlistTracks.map { it.id }.toSet().size)
        val exported = files.exportPlaylistsJson().jsonArray.single().jsonObject
        assertEquals(Json { ignoreUnknownKeys = true }.encodeToJsonElement(entry().playlist).jsonObject["description"], exported.getValue("playlist").jsonObject["description"])
        assertEquals(1700000000L, exported.getValue("playlist").jsonObject.getValue("created_at").jsonPrimitive.long)
    }

    @Test fun configKeepLocalUsesLegacyCfgSuffixWithoutMovingLocalRows() = runTest {
        val store = MemoryTransferStore(AnyPlayerStateData(listOf(playlist()), listOf(track())))
        val files = LegacyConfigFiles(PlaylistImporter(store), MemoryConnections())
        files.importJson(config(entry(tracks = listOf(ConfigTrack(1, "p", "plex", "song", 0, 1700000000, "Imported", "Artist")))), MergePolicy.MERGE_KEEP_LOCAL, false)
        assertEquals(track(), store.data.playlistTracks.first { it.playlistId == "p" })
        assertTrue(store.data.customPlaylists.any { it.id.startsWith("p_cfg_") })
    }
}
