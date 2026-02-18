package com.anyplayer.android.feature.playback

import android.media.AudioFormat
import android.media.AudioTrack
import xyz.gianlu.librespot.player.mixing.output.OutputAudioFormat
import xyz.gianlu.librespot.player.mixing.output.SinkException
import xyz.gianlu.librespot.player.mixing.output.SinkOutput
import java.io.IOException

/**
 * Android AudioTrack-based SinkOutput for librespot-java.
 *
 * Must have a no-arg constructor — librespot instantiates this via reflection
 * from PlayerConfiguration.setOutputClass(name).
 */
class LibrespotAndroidSink : SinkOutput {

    private var track: AudioTrack? = null
    private var lastVolume: Float = -1f

    override fun start(format: OutputAudioFormat): Boolean {
        if (format.sampleSizeInBits != 16) {
            throw SinkException("Unsupported sample size: ${format.sampleSizeInBits}", null)
        }
        if (format.channels < 1 || format.channels > 2) {
            throw SinkException("Unsupported channel count: ${format.channels}", null)
        }

        val channelConfig = if (format.channels == 1)
            AudioFormat.CHANNEL_OUT_MONO
        else
            AudioFormat.CHANNEL_OUT_STEREO

        val sampleRate = format.sampleRate.toInt()
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        try {
            track = AudioTrack.Builder()
                .setBufferSizeInBytes(minBufferSize)
                .setAudioFormat(audioFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: UnsupportedOperationException) {
            throw SinkException("AudioTrack creation failed", e.cause)
        }

        if (lastVolume != -1f) track?.setVolume(lastVolume)
        track?.play()
        return true
    }

    @Throws(IOException::class)
    override fun write(buffer: ByteArray, offset: Int, len: Int) {
        val outcome = track?.write(buffer, offset, len, AudioTrack.WRITE_BLOCKING)
            ?: throw IOException("AudioTrack not initialised")
        when (outcome) {
            AudioTrack.ERROR -> throw IOException("Generic AudioTrack write failure")
            AudioTrack.ERROR_BAD_VALUE -> throw IOException("Bad value writing to AudioTrack")
            AudioTrack.ERROR_DEAD_OBJECT -> throw IOException("AudioTrack object is dead")
            AudioTrack.ERROR_INVALID_OPERATION -> throw IOException("Invalid AudioTrack operation")
        }
    }

    override fun flush() {
        track?.flush()
    }

    override fun setVolume(volume: Float): Boolean {
        lastVolume = volume
        track?.setVolume(volume)
        return true
    }

    override fun release() {
        track?.release()
    }

    override fun stop() {
        val t = track ?: return
        if (t.playState != AudioTrack.PLAYSTATE_STOPPED) t.stop()
    }

    override fun close() {
        track = null
    }
}
