package com.anyplayer.android.feature.state.transfer

import com.anyplayer.android.BuildConfig
import com.anyplayer.android.core.model.ColumnPreferences
import com.anyplayer.android.core.model.CustomPlaylist
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.PlaylistTrack
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.UnionPlaylistSource
import com.anyplayer.android.core.storage.AppDatabase
import com.anyplayer.android.core.storage.dao.ColumnPreferenceDao
import com.anyplayer.android.core.storage.dao.CustomPlaylistDao
import com.anyplayer.android.core.storage.dao.PlaylistTrackDao
import com.anyplayer.android.core.storage.dao.UnionPlaylistSourceDao
import com.anyplayer.android.core.storage.mapper.toEntity
import com.anyplayer.android.core.storage.mapper.toModel
import com.anyplayer.android.feature.auth.SecureConnectionStore
import com.anyplayer.android.feature.auth.StoredConnection
import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StateTransferManager @Inject constructor(
    private val db: AppDatabase,
    private val customPlaylistDao: CustomPlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val unionPlaylistSourceDao: UnionPlaylistSourceDao,
    private val columnPreferenceDao: ColumnPreferenceDao,
    private val secureConnectionStore: SecureConnectionStore,
    private val json: Json
) {
    private val crypto = StateTransferCrypto()

    suspend fun exportToFile(target: File, options: ExportOptions, playbackStatus: PlaybackStatus?): File {
        val output = buildExportString(options, playbackStatus)
        target.writeText(output)
        return target
    }

    suspend fun exportToStream(stream: OutputStream, options: ExportOptions, playbackStatus: PlaybackStatus?) {
        val output = buildExportString(options, playbackStatus)
        stream.writer(Charsets.UTF_8).use { it.write(output) }
    }

    private suspend fun buildExportString(options: ExportOptions, playbackStatus: PlaybackStatus?): String {
        val playlists = customPlaylistDao.getAll().map { it.toModel() }
        val tracks = playlistTrackDao.getAll().map { it.toModel() }
        val unionSources = unionPlaylistSourceDao.getAll().map { it.toModel() }
        val columns = columnPreferenceDao.getAll().associate { it.key to ColumnPreferences(it.key, it.visible, it.position) }

        val connectionExports = secureConnectionStore.readAll().associate { connection ->
            connection.source to connection.toExport(options.mode)
        }

        val data = AnyPlayerStateData(
            customPlaylists = playlists,
            playlistTracks = tracks,
            unionPlaylistSources = unionSources,
            columnPreferences = columns,
            connections = connectionExports,
            playbackState = playbackStatus.takeIf { options.includePlaybackState }
        )

        val seedEnvelope = AnyPlayerStateEnvelope(
            createdAt = Instant.now().toString(),
            sourceApp = SourceAppInfo(platform = "android", appVersion = BuildConfig.VERSION_NAME),
            data = data,
            integrity = Integrity(sha256 = "")
        )

        val canonicalWithoutIntegrity = json.encodeToString(AnyPlayerStateEnvelope.serializer(), seedEnvelope)
        val integrity = Integrity(sha256 = crypto.sha256(canonicalWithoutIntegrity))
        val finalEnvelope = seedEnvelope.copy(integrity = integrity)
        val plainJson = json.encodeToString(AnyPlayerStateEnvelope.serializer(), finalEnvelope)

        return when (options.mode) {
            ExportMode.PORTABLE -> plainJson
            ExportMode.PRIVATE -> {
                val passphrase = requireNotNull(options.passphrase) { "Passphrase is required for private exports" }
                val encrypted = crypto.encrypt(plainJson, passphrase)
                json.encodeToString(EncryptedStateFile.serializer(), encrypted)
            }
        }
    }

    suspend fun importFromFile(source: File, options: ImportOptions): ImportSummary {
        val raw = source.readText()
        return importFromRaw(raw, options)
    }

    suspend fun importFromStream(stream: InputStream, options: ImportOptions): ImportSummary {
        val raw = stream.reader(Charsets.UTF_8).readText()
        return importFromRaw(raw, options)
    }

    private suspend fun importFromRaw(raw: String, options: ImportOptions): ImportSummary {
        val envelope = decodeEnvelope(raw, options.passphrase)
        validateEnvelope(envelope)
        return importEnvelope(envelope, options)
    }

    fun inspectFile(source: File): Pair<Int, Boolean> {
        val raw = source.readText()
        return if (raw.contains("\"encrypted\":true")) {
            ANY_PLAYER_STATE_VERSION to true
        } else {
            val parsed = json.decodeFromString(AnyPlayerStateEnvelope.serializer(), raw)
            parsed.version to false
        }
    }

    private suspend fun importEnvelope(envelope: AnyPlayerStateEnvelope, options: ImportOptions): ImportSummary {
        val existingPlaylists = customPlaylistDao.getAll().associateBy { it.id }
        val existingTracks = playlistTrackDao.getAll().associateBy { it.id }
        val existingUnionSources = unionPlaylistSourceDao.getAll().associateBy { it.id }

        val remap = mutableMapOf<String, String>()
        var playlistsAdded = 0
        var playlistsUpdated = 0

        val importedPlaylists = envelope.data.customPlaylists.map { incoming ->
            val existing = existingPlaylists[incoming.id]
            if (existing == null) {
                playlistsAdded += 1
                incoming
            } else {
                when (options.mergePolicy) {
                    MergePolicy.REPLACE_ALL,
                    MergePolicy.MERGE_PREFER_IMPORT -> {
                        playlistsUpdated += 1
                        incoming
                    }

                    MergePolicy.MERGE_KEEP_LOCAL -> {
                        val changed = existing.name != incoming.name || existing.updatedAt != incoming.updatedAt
                        if (changed) {
                            val newId = deterministicRemap(incoming.id)
                            remap[incoming.id] = newId
                            playlistsAdded += 1
                            incoming.copy(id = newId)
                        } else {
                            incoming.copy(id = existing.id)
                        }
                    }
                }
            }
        }

        val importedTracks = envelope.data.playlistTracks.map { track ->
            val newPlaylistId = remap[track.playlistId] ?: track.playlistId
            track.copy(
                id = remap[track.id] ?: track.id,
                playlistId = newPlaylistId
            )
        }

        val importedUnionSources = envelope.data.unionPlaylistSources.map { source ->
            source.copy(
                id = remap[source.id] ?: source.id,
                unionPlaylistId = remap[source.unionPlaylistId] ?: source.unionPlaylistId,
                sourcePlaylistId = remap[source.sourcePlaylistId] ?: source.sourcePlaylistId
            )
        }

        validateReferences(importedPlaylists, importedTracks, importedUnionSources)

        val tracksAdded = importedTracks.count { !existingTracks.containsKey(it.id) }
        val tracksUpdated = importedTracks.size - tracksAdded
        val linksAdded = importedUnionSources.count { !existingUnionSources.containsKey(it.id) }
        val linksUpdated = importedUnionSources.size - linksAdded

        val warnings = mutableListOf<String>()
        val connectionsImported = importConnections(envelope, options, warnings)

        if (!options.dryRun) {
            db.withTransaction {
                if (options.mergePolicy == MergePolicy.REPLACE_ALL) {
                    unionPlaylistSourceDao.deleteAll()
                    playlistTrackDao.deleteAll()
                    customPlaylistDao.deleteAll()
                }

                customPlaylistDao.upsert(importedPlaylists.map { it.toEntity() })
                playlistTrackDao.upsert(importedTracks.map { it.toEntity() })
                unionPlaylistSourceDao.upsert(importedUnionSources.map { it.toEntity() })
                columnPreferenceDao.upsert(envelope.data.columnPreferences.values.map { it.toEntity() })
            }
        }

        return ImportSummary(
            playlistsAdded = playlistsAdded,
            playlistsUpdated = playlistsUpdated,
            tracksAdded = tracksAdded,
            tracksUpdated = tracksUpdated,
            unionLinksAdded = linksAdded,
            unionLinksUpdated = linksUpdated,
            connectionsImported = connectionsImported,
            connectionsSkipped = envelope.data.connections.size - connectionsImported,
            warnings = warnings
        )
    }

    private fun importConnections(
        envelope: AnyPlayerStateEnvelope,
        options: ImportOptions,
        warnings: MutableList<String>
    ): Int {
        var imported = 0
        envelope.data.connections.values.forEach { connection ->
            val includeSecrets = options.passphrase != null
            val tokenValue = if (includeSecrets) connection.token ?: connection.apiKey else null
            if (connection.source == SourceType.SPOTIFY && tokenValue != null) {
                warnings += "Spotify raw access token ignored by policy"
            }

            val stored = StoredConnection(
                source = connection.source,
                serverUrl = connection.serverUrl,
                username = connection.username,
                token = if (connection.source == SourceType.SPOTIFY) null else tokenValue,
                spotifyPremium = connection.spotifyPremium,
                playbackReady = connection.source != SourceType.SPOTIFY
            )

            val hasUsefulData = !stored.serverUrl.isNullOrBlank() || !stored.token.isNullOrBlank() || connection.spotifyConnected == true
            if (hasUsefulData) {
                secureConnectionStore.save(stored)
                imported += 1
            }
        }
        return imported
    }

    private fun StoredConnection.toExport(mode: ExportMode): ExportedConnection {
        val includeSecrets = mode == ExportMode.PRIVATE
        return ExportedConnection(
            source = source,
            serverUrl = serverUrl,
            username = username,
            token = if (includeSecrets && source != SourceType.SPOTIFY) token else null,
            apiKey = if (includeSecrets && source == SourceType.JELLYFIN) token else null,
            spotifyConnected = source == SourceType.SPOTIFY,
            spotifyPremium = spotifyPremium
        )
    }

    private fun validateEnvelope(envelope: AnyPlayerStateEnvelope) {
        require(envelope.format == ANY_PLAYER_STATE_FORMAT) { "Invalid format" }
        require(envelope.version == ANY_PLAYER_STATE_VERSION) { "Unsupported state version: ${envelope.version}" }

        val expected = envelope.integrity.sha256
        val withEmptyIntegrity = envelope.copy(integrity = Integrity(sha256 = ""))
        val normalized = json.encodeToString(AnyPlayerStateEnvelope.serializer(), withEmptyIntegrity)
        val actual = crypto.sha256(normalized)
        require(actual == expected) { "Integrity check failed" }
    }

    private fun validateReferences(
        playlists: List<CustomPlaylist>,
        tracks: List<PlaylistTrack>,
        unionSources: List<UnionPlaylistSource>
    ) {
        val playlistIds = playlists.map { it.id }.toSet()
        tracks.forEach { track ->
            require(track.playlistId in playlistIds) {
                "Malformed reference: track ${track.id} points to missing playlist ${track.playlistId}"
            }
        }

        unionSources.forEach { source ->
            val hasLocalTarget = source.unionPlaylistId in playlistIds
            val hasProviderReference =
                source.sourceType != SourceType.CUSTOM || source.sourcePlaylistId in playlistIds
            require(hasLocalTarget && hasProviderReference) {
                "Malformed union source reference: ${source.id}"
            }
        }

        playlists.filter { it.playlistType == PlaylistType.UNION }.forEach { unionPlaylist ->
            val hasSource = unionSources.any { it.unionPlaylistId == unionPlaylist.id }
            require(hasSource) { "Union playlist ${unionPlaylist.id} has no sources" }
        }
    }

    private fun deterministicRemap(id: String): String = "${id}_import_${UUID.nameUUIDFromBytes(id.toByteArray())}"

    private fun decodeEnvelope(raw: String, passphrase: String?): AnyPlayerStateEnvelope {
        return if (raw.contains("\"encrypted\":true")) {
            val encrypted = json.decodeFromString(EncryptedStateFile.serializer(), raw)
            val value = crypto.decrypt(
                file = encrypted,
                passphrase = requireNotNull(passphrase) { "Passphrase required for encrypted import" }
            )
            json.decodeFromString(AnyPlayerStateEnvelope.serializer(), value)
        } else {
            json.decodeFromString(AnyPlayerStateEnvelope.serializer(), raw)
        }
    }
}
