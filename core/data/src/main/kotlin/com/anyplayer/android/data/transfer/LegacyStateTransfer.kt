package com.anyplayer.android.data.transfer

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.ConnectionStore
import com.anyplayer.android.data.auth.StoredConnection
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

@Singleton
class LegacyStateTransfer @Inject constructor(
    private val importer: PlaylistImporter,
    private val connections: ConnectionStore,
    /** Legacy wrote `BuildConfig.VERSION_NAME` into `sourceApp.appVersion`; the app module binds it. */
    @Named("appVersionName") private val appVersionName: String,
) : StateTransfer {
    private val crypto = StateTransferCrypto()

    override suspend fun export(output: OutputStream, mode: ExportMode, passphrase: String?, playback: PlaybackStatus?) = withContext(Dispatchers.IO) {
        if (mode == ExportMode.PRIVATE) requireNotNull(passphrase) { "Passphrase is required for private exports" }
        val stored = connections.awaitLoaded()
        // Only the snapshot reads run in a transaction; PBKDF2 and the (possibly slow SAF) write follow it.
        val snapshot = importer.snapshot()
        val data = snapshot.copy(connections = stored.mapValues { (_, connection) -> connection.toExport(mode) }, playbackState = playback)
        val seed = AnyPlayerStateEnvelope(createdAt = Instant.now().toString(), sourceApp = SourceAppInfo("android", appVersionName), data = data, integrity = Integrity(""))
        val envelope = seed.copy(integrity = Integrity(crypto.sha256(AppJson.encodeToString(seed))))
        val plain = AppJson.encodeToString(envelope)
        val encoded = if (mode == ExportMode.PRIVATE) AppJson.encodeToString(crypto.encrypt(plain, requireNotNull(passphrase))) else plain
        output.writer(Charsets.UTF_8).use { it.write(encoded) }
    }

    override suspend fun import(input: InputStream, policy: MergePolicy, passphrase: String?, dryRun: Boolean): ImportSummary = withContext(Dispatchers.IO) {
        // Read, decrypt and verify before touching the database; only the apply step is transactional.
        val raw = input.reader(Charsets.UTF_8).use { it.readText() }
        val envelope = decodeAndVerify(raw, passphrase)
        val summary = importer.import(envelope.data, policy, dryRun, "import")
        val warnings = mutableListOf<String>()
        var imported = 0
        for (connection in envelope.data.connections.values) {
            val token = if (passphrase != null) connection.token ?: connection.apiKey else null
            if (connection.source == SourceType.SPOTIFY && token != null) warnings += "Spotify raw access token ignored by policy"
            val acceptedToken = token?.takeIf { it.isNotBlank() && connection.source != SourceType.SPOTIFY }
            if (connection.serverUrl.isNullOrBlank() && acceptedToken == null && connection.spotifyConnected != true) continue
            if (!dryRun) connections.update(connection.source) { existing ->
                val local = existing ?: StoredConnection(connection.source)
                local.copy(
                    serverUrl = connection.serverUrl?.takeIf { it.isNotBlank() } ?: local.serverUrl,
                    username = connection.username ?: local.username,
                    token = acceptedToken ?: local.token,
                    spotifyPremium = connection.spotifyPremium ?: local.spotifyPremium,
                    playbackReady = local.playbackReady ?: (connection.source != SourceType.SPOTIFY),
                )
            }
            imported++
        }
        // Like playlists, MERGE_KEEP_LOCAL keeps the local player flags; dry runs change nothing.
        val playback = envelope.data.playbackState?.takeIf { !dryRun && policy != MergePolicy.MERGE_KEEP_LOCAL }
        summary.copy(connectionsImported = imported, connectionsSkipped = envelope.data.connections.size - imported, warnings = warnings,
            playbackSettings = playback?.let { ImportedPlaybackSettings(it.volume, it.shuffle, it.repeatMode) })
    }

    internal fun decodeAndVerify(raw: String, passphrase: String?): AnyPlayerStateEnvelope {
        val root = AppJson.parseToJsonElement(raw).jsonObject
        val encrypted = root["encrypted"]?.jsonPrimitive?.booleanOrNull == true ||
            (listOf("saltBase64", "nonceBase64", "ciphertextBase64").all { root[it]?.jsonPrimitive?.contentOrNull != null } && root["kdfIterations"]?.jsonPrimitive != null)
        val plain = if (encrypted) crypto.decrypt(AppJson.decodeFromString<EncryptedStateFile>(raw), requireNotNull(passphrase) { "Passphrase required for encrypted import" }) else raw
        val envelope = AppJson.decodeFromString<AnyPlayerStateEnvelope>(plain)
        require(envelope.format == ANY_PLAYER_STATE_FORMAT) { "Invalid format" }
        require(envelope.version == ANY_PLAYER_STATE_VERSION) { "Unsupported state version: ${envelope.version}" }
        require(crypto.sha256(AppJson.encodeToString(envelope.copy(integrity = Integrity("")))) == envelope.integrity.sha256) { "Integrity check failed" }
        return envelope
    }

    private fun StoredConnection.toExport(mode: ExportMode) = ExportedConnection(
        source, serverUrl, username,
        token = token.takeIf { mode == ExportMode.PRIVATE && source != SourceType.SPOTIFY },
        apiKey = token.takeIf { mode == ExportMode.PRIVATE && source == SourceType.JELLYFIN },
        spotifyConnected = source == SourceType.SPOTIFY, spotifyPremium = spotifyPremium,
    )
}
