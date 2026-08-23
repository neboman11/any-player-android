package com.anyplayer.android.core.rust

import com.anyplayer.android.core.log.CompatLog

internal object RustBridgeNative {
    private const val TAG = "RustBridgeNative"

    val isLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("any_player_ffi_android")
            true
        }.getOrElse { error ->
            CompatLog.e(TAG, "Rust JNI library unavailable; Kotlin fallback remains active", error)
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
    external fun getAudioNormalizationSettings(): String

    @JvmStatic
    external fun setAudioNormalizationSettings(configJson: String): String

    @JvmStatic
    external fun applyAudioNormalizationVolume(configJson: String): String

    @JvmStatic
    external fun providerApiCall(configJson: String): String
}
