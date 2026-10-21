package com.anyplayer.android.sync

import android.content.Context
import com.anyplayer.android.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Merge base for app-state sync: the [appStateSignature] this device last pushed to, or applied from,
 * a sync target. Persisted so that playback changed while the server was unreachable (or before sync
 * started, e.g. under Android Auto) is still recognised as newer than the server copy after a restart.
 */
interface AppStateBaseStore {
    /** [target] is the normalized server base URL; a base recorded for another target is ignored. */
    suspend fun get(target: String): String?
    suspend fun set(target: String, signature: String)
}

@Singleton
class SharedPreferencesAppStateBaseStore @Inject constructor(@ApplicationContext private val context: Context) : AppStateBaseStore {
    private val prefs by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    override suspend fun get(target: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_SIGNATURE, null)?.takeIf { prefs.getString(KEY_TARGET, null) == target }
    }
    override suspend fun set(target: String, signature: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_TARGET, target).putString(KEY_SIGNATURE, signature).apply()
    }
    private companion object {
        const val FILE = "sync_app_state_base"
        const val KEY_TARGET = "target"
        const val KEY_SIGNATURE = "signature"
    }
}

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
    val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
