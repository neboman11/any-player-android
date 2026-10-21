package com.anyplayer.android.sync

import android.content.Context
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.net.normalizeSyncServerAuthToken
import com.anyplayer.android.data.net.normalizeSyncServerBaseUrl
import com.anyplayer.android.data.settings.SyncPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Sync domains with a merge base: the signature of what this device last pushed to, or applied from, a
 * sync target. Persisted so that local changes made while the server was unreachable (or before sync
 * started, e.g. under Android Auto) are still recognised as newer than the server copy after a restart,
 * and so that applying a remote copy is never echoed back as a local change.
 */
enum class SyncBase(internal val keyPrefix: String) {
    /** [appStateSignature]. Legacy URL-only bases are deliberately not reused. */
    APP_STATE(""),
    /** [playlistsSignature] of the exported playlists JSON. */
    PLAYLISTS("playlists_"),
    /** [settingsSignature] of the settings namespace JSON. */
    SETTINGS("settings_"),
}

interface SyncBaseStore {
    /** [target] includes the normalized server URL and a one-way hash of its normalized credential. */
    suspend fun get(domain: SyncBase, target: String): String?
    suspend fun set(domain: SyncBase, target: String, signature: String)
    suspend fun version(target: String): Long?
    suspend fun recordVersion(target: String, version: Long)
    suspend fun clear(target: String)
}

@Singleton
class SharedPreferencesSyncBaseStore @Inject constructor(@ApplicationContext private val context: Context) : SyncBaseStore {
    private val prefs by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    override suspend fun get(domain: SyncBase, target: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(domain.keyPrefix + KEY_SIGNATURE, null)?.takeIf { prefs.getString(domain.keyPrefix + KEY_TARGET, null) == target }
    }
    override suspend fun set(domain: SyncBase, target: String, signature: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(domain.keyPrefix + KEY_TARGET, target).putString(domain.keyPrefix + KEY_SIGNATURE, signature).apply()
    }
    override suspend fun version(target: String): Long? = withContext(Dispatchers.IO) {
        prefs.getLong("version_$target", -1).takeIf { it >= 0 }
    }
    override suspend fun recordVersion(target: String, version: Long) = withContext(Dispatchers.IO) {
        prefs.edit().putLong("version_$target", version).apply()
    }
    override suspend fun clear(target: String) = withContext(Dispatchers.IO) {
        val editor = prefs.edit().remove("version_$target")
        SyncBase.entries.forEach { domain ->
            if (prefs.getString(domain.keyPrefix + KEY_TARGET, null) == target) {
                editor.remove(domain.keyPrefix + KEY_TARGET).remove(domain.keyPrefix + KEY_SIGNATURE)
            }
        }
        editor.apply()
    }
    private companion object {
        // Retain the file name, but URL-only legacy targets never match a credential-keyed target.
        const val FILE = "sync_app_state_base"
        const val KEY_TARGET = "target"
        const val KEY_SIGNATURE = "signature"
    }
}

internal fun syncBaseTarget(preferences: SyncPreferences): String =
    normalizeSyncServerBaseUrl(preferences.serverTarget) + "#" + sha256(normalizeSyncServerAuthToken(preferences.authToken))

internal fun sameTrack(a: Track, b: Track) = a.id == b.id && a.source == b.source

/**
 * Queue content only (current track, queue, shuffle, repeat), normalized the way remote state is
 * applied: a current track missing from the queue is prepended. Transport (state, position, volume)
 * is excluded because it changes continuously.
 */
internal fun appStateSignature(current: Track?, queue: List<Track>, shuffle: Boolean?, repeat: String): String {
    val tracks = if (current != null && queue.none { sameTrack(it, current) }) listOf(current) + queue else queue
    val parts = listOf(shuffle.toString(), repeat, current?.let { "${it.source}:${it.id}" }.orEmpty()) +
        tracks.map { "${it.source}:${it.id}" }
    return sha256(parts.joinToString("\u0000"))
}

/**
 * Playlists namespace content, independent of the exporting client's formatting: playlists ordered by id,
 * tracks and union sources by position, file-local row `id`s and null fields dropped, keys sorted. A
 * missing namespace is the empty list.
 */
internal fun playlistsSignature(playlists: JsonElement?): String {
    fun rows(element: JsonElement?) = JsonArray((element as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        .sortedBy { it.number("position") }.map { JsonObject(it - "id") })
    val entries = (playlists as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        .map { JsonObject(it + mapOf("tracks" to rows(it["tracks"]), "union_sources" to rows(it["union_sources"]))) }
        .sortedBy { (it["playlist"] as? JsonObject)?.string("id") }
    return sha256(canonical(JsonArray(entries)).toString())
}

/** The settings namespace as this device pushes it. */
internal fun settingsJson(normalizationEnabled: Boolean, normalizationStrict: Boolean) = buildJsonObject {
    put("audio_normalization_enabled", normalizationEnabled)
    put("audio_normalization_strict_mode", normalizationStrict)
}

internal fun settingsSignature(settings: JsonElement): String = sha256(canonical(settings).toString())

private fun canonical(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.filterValues { it !is JsonNull }.toSortedMap().mapValues { canonical(it.value) })
    is JsonArray -> JsonArray(element.map(::canonical))
    else -> element
}

private fun sha256(text: String) =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
