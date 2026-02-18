package com.anyplayer.android

import android.app.Application
import com.anyplayer.android.feature.playback.LibrespotAndroidDecoder
import dagger.hilt.android.HiltAndroidApp
import xyz.gianlu.librespot.audio.decoders.Decoders
import xyz.gianlu.librespot.audio.format.SuperAudioFormat

@HiltAndroidApp
class AnyPlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Register Android-native MediaCodec decoders for librespot-java.
        // Priority 0 = highest; these override the default Java-based decoders.
        Decoders.registerDecoder(SuperAudioFormat.VORBIS, 0, LibrespotAndroidDecoder::class.java)
        Decoders.registerDecoder(SuperAudioFormat.MP3, 0, LibrespotAndroidDecoder::class.java)
    }
}
