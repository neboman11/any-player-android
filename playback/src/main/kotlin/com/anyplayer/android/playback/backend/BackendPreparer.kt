package com.anyplayer.android.playback.backend

/**
 * Optional warm-up for a backend that is about to receive a run (e.g. Spotify device resolution
 * ~20 s before an EXO→SPOTIFY boundary). Implemented by backends in the PlaybackBackend map.
 * Warm-up never loads a run, starts audio, or emits backend events.
 */
fun interface BackendPreparer {
    suspend fun prepare(): Boolean
}
