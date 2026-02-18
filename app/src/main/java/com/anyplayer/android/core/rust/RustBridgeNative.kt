package com.anyplayer.android.core.rust

import android.util.Log

internal object RustBridgeNative {
    private const val TAG = "RustBridgeNative"

    val isLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("any_player_ffi_android")
            true
        }.getOrElse { error ->
            Log.w(TAG, "Rust JNI library unavailable; Kotlin fallback remains active", error)
            false
        }
    }

    @JvmStatic
    external fun init(configJson: String): String

    @JvmStatic
    external fun spotifyBeginAuth(configJson: String): String

    @JvmStatic
    external fun spotifyExchangeCode(code: String, verifier: String, redirect: String): String

    @JvmStatic
    external fun spotifyValidateToken(tokenInput: String): String

    @JvmStatic
    external fun spotifyInitSession(tokenInput: String): String

    @JvmStatic
    external fun spotifySessionReady(): String

    @JvmStatic
    external fun spotifyStartQueue(configJson: String): String

    @JvmStatic
    external fun spotifyPlay(): String

    @JvmStatic
    external fun spotifyPause(): String

    @JvmStatic
    external fun spotifyNext(): String

    @JvmStatic
    external fun spotifyPrevious(): String

    @JvmStatic
    external fun spotifySeek(configJson: String): String

    @JvmStatic
    external fun spotifySetVolume(configJson: String): String

    @JvmStatic
    external fun spotifySetShuffle(configJson: String): String

    @JvmStatic
    external fun spotifySetRepeatMode(configJson: String): String

    @JvmStatic
    external fun spotifySnapshot(): String
}
