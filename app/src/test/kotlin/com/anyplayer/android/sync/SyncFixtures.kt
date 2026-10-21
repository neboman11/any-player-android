package com.anyplayer.android.sync

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.playlists.PlaylistRepository
import com.anyplayer.android.data.settings.*
import com.anyplayer.android.data.transfer.*
import com.anyplayer.android.playback.*
import java.io.InputStream
import java.lang.reflect.Proxy
import kotlin.random.Random
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
    error("Unexpected ${T::class.java.simpleName}.${method.name}")
} as T

internal val song = Track("one", "One", "Artist", source = SourceType.JELLYFIN)
internal val secondSong = song.copy(id = "two", title = "Two")
internal val localPlaylist = CustomPlaylist("local", "Local", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z", trackCount = 0, playlistType = PlaylistType.STANDARD)

internal class FakeEngine : PlaybackEngine {
    override val queue = MutableStateFlow(QueueState())
    override val player = MutableStateFlow(PlayerState())
    override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 32)
    override val changes = MutableSharedFlow<EngineChange>(extraBufferCapacity = 32)
    val calls = mutableListOf<String>()
    val origins = mutableListOf<ChangeOrigin>()
    var onRestore: suspend () -> Unit = {}
    override suspend fun awaitRestored() { onRestore() }
    override fun positionMs() = player.value.anchorPositionMs
    private fun changed(call: String, origin: ChangeOrigin, content: Boolean = false) {
        calls += call; origins += origin; changes.tryEmit(EngineChange(origin, content))
    }
    override fun setQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean, origin: ChangeOrigin) {
        queue.value = QueueOps.replace(queue.value, tracks, startIndex, Random(1))
        changed("queue:$autoPlay", origin, true)
    }
    override fun play(origin: ChangeOrigin) { player.value = player.value.copy(status = PlaybackStateType.PLAYING); changed("play", origin) }
    override fun pause(origin: ChangeOrigin) { player.value = player.value.copy(status = PlaybackStateType.PAUSED); changed("pause", origin) }
    override fun seekTo(ms: Long, origin: ChangeOrigin) { player.value = player.value.copy(anchorPositionMs = ms); changed("seek:$ms", origin) }
    override fun setVolume(percent: Int, origin: ChangeOrigin) { player.value = player.value.copy(volume = percent); changed("volume:$percent", origin) }
    override fun setShuffle(on: Boolean, origin: ChangeOrigin) { queue.value = queue.value.copy(shuffle = on); changed("shuffle:$on", origin, true) }
    override fun setRepeat(mode: RepeatMode, origin: ChangeOrigin) { queue.value = queue.value.copy(repeat = mode); changed("repeat:$mode", origin) }
    override fun setNormalization(enabled: Boolean, strict: Boolean) { calls += "normalization:$enabled:$strict" }
    override fun playNext(track: Track) { queue.value = QueueOps.playNext(queue.value, track); changed("playNext", ChangeOrigin.USER, true) }
    override fun playEntry(uid: Long) { error("unused") }
    override fun togglePlayPause() { error("unused") }
    override fun next() { error("unused") }
    override fun previous() { error("unused") }
    override fun setPendingBreak(pendingBreak: PendingBreak?) { error("unused") }
}

internal class FakeSyncSettings(initial: SyncPreferences) : SyncSettingsStore {
    override val preferences = MutableStateFlow(initial)
    override suspend fun awaitLoaded() = preferences.value
    override suspend fun update(transform: (SyncPreferences) -> SyncPreferences) { preferences.value = transform(preferences.value) }
    override suspend fun clientId() = "this-client"
}

internal class FakeSyncBase : SyncBaseStore {
    val values = MutableStateFlow<Map<Pair<SyncBase, String>, String>>(emptyMap())
    private val versions = mutableMapOf<String, Long>()
    override suspend fun get(domain: SyncBase, target: String) = values.value[domain to target]
    override suspend fun set(domain: SyncBase, target: String, signature: String) { values.value += (domain to target) to signature }
    override suspend fun version(target: String) = versions[target]
    override suspend fun recordVersion(target: String, version: Long) { versions[target] = version }
    override suspend fun clear(target: String) {
        values.value = values.value.filterKeys { it.second != target }
        versions.remove(target)
    }
}

internal class FakePlayerSettings : PlayerSettingsStore {
    override val settings = MutableStateFlow(PlayerSettings())
    override suspend fun awaitLoaded() = settings.value
    override fun update(transform: (PlayerSettings) -> PlayerSettings) { settings.value = transform(settings.value) }
}

internal class FakePlaylists : PlaylistRepository by unused<PlaylistRepository>() {
    override val customPlaylists = MutableStateFlow<List<CustomPlaylist>>(emptyList())
}

/** [playlists] stands in for Room: REPLACE_ALL imports replace it and exports return it. */
internal class FakeConfig : ConfigFiles {
    val imports = mutableListOf<Pair<JsonElement, MergePolicy>>()
    var playlists: JsonElement = JsonArray(emptyList())
    var summary = ImportSummary()
    var onImport: suspend () -> Unit = {}
    override suspend fun importJson(config: JsonElement, policy: MergePolicy, dryRun: Boolean): ImportSummary {
        check(!dryRun); onImport(); imports += config to policy
        if (policy == MergePolicy.REPLACE_ALL) playlists = config.jsonObject.getValue("custom_playlists")
        return summary
    }
    override suspend fun import(input: InputStream, policy: MergePolicy, dryRun: Boolean): ImportSummary = error("unused")
    override suspend fun exportPlaylistsJson(): JsonElement = playlists
    override suspend fun exportProviderConfigJson(): JsonElement = JsonObject(emptyMap())
}
