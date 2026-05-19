package com.anyplayer.android.feature.state.transfer

import com.anyplayer.android.core.di.CoreModule
import com.anyplayer.android.core.storage.AppDatabase
import com.anyplayer.android.core.storage.dao.ColumnPreferenceDao
import com.anyplayer.android.core.storage.dao.CustomPlaylistDao
import com.anyplayer.android.core.storage.dao.PlaylistTrackDao
import com.anyplayer.android.core.storage.dao.UnionPlaylistSourceDao
import com.anyplayer.android.feature.auth.SecureConnectionStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.File

class StateTransferManagerTest {
    private val db = mock<AppDatabase>()
    private val customPlaylistDao = mock<CustomPlaylistDao>()
    private val playlistTrackDao = mock<PlaylistTrackDao>()
    private val unionPlaylistSourceDao = mock<UnionPlaylistSourceDao>()
    private val columnPreferenceDao = mock<ColumnPreferenceDao>()
    private val secureConnectionStore = mock<SecureConnectionStore>()
    private val json: Json = CoreModule.provideJson()
    private val crypto = StateTransferCrypto()

    private lateinit var manager: StateTransferManager

    @Before
    fun setUp() {
        manager = StateTransferManager(
            db = db,
            customPlaylistDao = customPlaylistDao,
            playlistTrackDao = playlistTrackDao,
            unionPlaylistSourceDao = unionPlaylistSourceDao,
            columnPreferenceDao = columnPreferenceDao,
            secureConnectionStore = secureConnectionStore,
            json = json
        )
    }

    @Test
    fun inspectFile_privateExportWithoutEncodedDefaults_reportsEncrypted() {
        val tempFile = File.createTempFile("state-transfer", ".json")
        tempFile.writeText(buildPrivateExportJson())

        val (version, encrypted) = manager.inspectFile(tempFile)

        assertEquals(ANY_PLAYER_STATE_VERSION, version)
        assertTrue(encrypted)

        tempFile.delete()
    }

    @Test
    fun inspectFile_portableExport_reportsPlain() {
        val tempFile = File.createTempFile("state-transfer-plain", ".json")
        tempFile.writeText(buildPortableExportJson())

        val (version, encrypted) = manager.inspectFile(tempFile)

        assertEquals(ANY_PLAYER_STATE_VERSION, version)
        assertFalse(encrypted)

        tempFile.delete()
    }

    @Test
    fun importFromStream_privateExportWithoutEncodedDefaults_importsSuccessfully() = runTest {
        whenever(customPlaylistDao.getAll()).thenReturn(emptyList())
        whenever(playlistTrackDao.getAll()).thenReturn(emptyList())
        whenever(unionPlaylistSourceDao.getAll()).thenReturn(emptyList())

        val raw = buildPrivateExportJson()
        val summary = manager.importFromStream(
            stream = ByteArrayInputStream(raw.toByteArray()),
            options = ImportOptions(
                mergePolicy = MergePolicy.MERGE_PREFER_IMPORT,
                passphrase = PASSPHRASE,
                dryRun = true
            )
        )

        assertEquals(
            ImportSummary(
                playlistsAdded = 0,
                playlistsUpdated = 0,
                tracksAdded = 0,
                tracksUpdated = 0,
                unionLinksAdded = 0,
                unionLinksUpdated = 0,
                connectionsImported = 0,
                connectionsSkipped = 0,
                warnings = emptyList()
            ),
            summary
        )
    }

    @Test
    fun importFromStream_privatePrettyPrintedExport_importsSuccessfully() = runTest {
        whenever(customPlaylistDao.getAll()).thenReturn(emptyList())
        whenever(playlistTrackDao.getAll()).thenReturn(emptyList())
        whenever(unionPlaylistSourceDao.getAll()).thenReturn(emptyList())

        val raw = buildPrivateExportJson(
            json = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                prettyPrint = true
                encodeDefaults = true
            }
        )

        val summary = manager.importFromStream(
            stream = ByteArrayInputStream(raw.toByteArray()),
            options = ImportOptions(
                mergePolicy = MergePolicy.MERGE_PREFER_IMPORT,
                passphrase = PASSPHRASE,
                dryRun = true
            )
        )

        assertEquals(0, summary.playlistsAdded)
        assertEquals(0, summary.tracksAdded)
        assertEquals(0, summary.unionLinksAdded)
    }

    private fun buildPortableExportJson(): String {
        val envelope = buildEnvelope()
        return json.encodeToString(AnyPlayerStateEnvelope.serializer(), envelope)
    }

    private fun buildPrivateExportJson(json: Json = this.json): String {
        val envelopeJson = buildPortableExportJson()
        val encrypted = crypto.encrypt(envelopeJson, PASSPHRASE)
        return json.encodeToString(EncryptedStateFile.serializer(), encrypted)
    }

    private fun buildEnvelope(): AnyPlayerStateEnvelope {
        val seed = AnyPlayerStateEnvelope(
            createdAt = "2026-05-18T12:00:00Z",
            sourceApp = SourceAppInfo(platform = "android", appVersion = "test"),
            data = AnyPlayerStateData(),
            integrity = Integrity(sha256 = "")
        )
        val canonical = json.encodeToString(AnyPlayerStateEnvelope.serializer(), seed)
        return seed.copy(integrity = Integrity(sha256 = crypto.sha256(canonical)))
    }

    companion object {
        private const val PASSPHRASE = "secret-pass"
    }
}
