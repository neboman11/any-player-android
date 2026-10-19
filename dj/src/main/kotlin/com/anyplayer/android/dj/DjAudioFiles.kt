package com.anyplayer.android.dj

import java.io.File

internal data class AudioKey(val trackId: String, val chunkIds: List<Long>)
internal data class CachedAudio(val file: File)

/** Owns cache copies and delivery lifetimes. Mutations run on IO and serialize file ownership. */
internal class DjAudioFiles {
    private data class Delivery(val key: AudioKey, val file: File, var started: Boolean = false)
    private val finished = linkedMapOf<AudioKey, CachedAudio>()
    private val deliveries = mutableMapOf<String, Delivery>()

    @Synchronized fun cached(key: AudioKey): CachedAudio? = finished[key]
    @Synchronized fun remember(key: AudioKey, audio: CachedAudio) {
        finished.put(key, audio)?.takeIf { it.file != audio.file }?.file?.delete()
        while (finished.size > 3) {
            val oldest = finished.keys.first()
            finished.remove(oldest)?.file?.delete()
        }
    }
    @Synchronized fun forgetTrack(trackId: String) {
        val iterator = finished.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.key.trackId == trackId) { item.value.file.delete(); iterator.remove() }
        }
    }
    @Synchronized fun deliver(id: String, key: AudioKey, file: File) { deliveries[id] = Delivery(key, file) }
    @Synchronized fun started(id: String) {
        val delivery = deliveries[id] ?: return
        delivery.started = true
        finished.remove(delivery.key)?.file?.delete()
    }
    /** A started break belongs to playback until BreakEnded, even after a spurious drop. */
    @Synchronized fun dropped(id: String): Boolean {
        val delivery = deliveries[id] ?: return false
        if (delivery.started) return false
        deliveries.remove(id)
        delivery.file.delete()
        return true
    }
    @Synchronized fun ended(id: String) { deliveries.remove(id)?.file?.delete() }
    @Synchronized fun clearFinished() { finished.values.forEach { it.file.delete() }; finished.clear() }
}
