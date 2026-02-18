package com.anyplayer.android.feature.playback;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import xyz.gianlu.librespot.player.decoders.Decoder;
import xyz.gianlu.librespot.player.decoders.SeekableInputStream;
import xyz.gianlu.librespot.player.mixing.output.OutputAudioFormat;

/**
 * Android MediaCodec/MediaExtractor-based decoder for librespot-java.
 * Handles both Ogg Vorbis and MP3 audio from Spotify streams.
 *
 * Mirrors AndroidNativeDecoder from the librespot-android demo project.
 */
public final class LibrespotAndroidDecoder extends Decoder {

    private static final String TAG = LibrespotAndroidDecoder.class.getSimpleName();

    private final byte[] buffer = new byte[2 * BUFFER_SIZE];
    private final MediaCodec codec;
    private final MediaExtractor extractor;
    private final Object closeLock = new Object();
    private long presentationTime = 0;

    public LibrespotAndroidDecoder(@NotNull SeekableInputStream audioIn, float normalizationFactor, int duration)
            throws IOException, DecoderException {
        super(audioIn, normalizationFactor, duration);

        final int start = audioIn.position();

        extractor = new MediaExtractor();
        extractor.setDataSource(new MediaDataSource() {
            @Override
            public int readAt(long position, byte[] buf, int offset, int size) throws IOException {
                audioIn.seek((int) position + start);
                return audioIn.read(buf, offset, size);
            }

            @Override
            public long getSize() {
                return audioIn.size() - start;
            }

            @Override
            public void close() {
                audioIn.close();
            }
        });

        if (extractor.getTrackCount() == 0) {
            throw new DecoderException("No tracks found in audio stream.");
        }

        extractor.selectTrack(0);

        MediaFormat format = extractor.getTrackFormat(0);
        String mime = format.getString(MediaFormat.KEY_MIME);

        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();

        int sampleSize = 16;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int pcmEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            switch (pcmEncoding) {
                case AudioFormat.ENCODING_PCM_8BIT:
                    sampleSize = 8;
                    break;
                case AudioFormat.ENCODING_PCM_16BIT:
                    sampleSize = 16;
                    break;
                default:
                    throw new DecoderException("Unsupported PCM encoding: " + pcmEncoding);
            }
        }

        setAudioFormat(new OutputAudioFormat(
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                sampleSize,
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                true,
                false
        ));
    }

    @Override
    protected int readInternal(@NotNull OutputStream out) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (true) {
            if (closed) return -1;

            synchronized (closeLock) {
                int inputBufferId = codec.dequeueInputBuffer(-1);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
                    int count = extractor.readSampleData(inputBuffer, 0);
                    if (count == -1) {
                        codec.signalEndOfInputStream();
                        return -1;
                    }
                    codec.queueInputBuffer(inputBufferId, 0, count, extractor.getSampleTime(), 0);
                    extractor.advance();
                }

                int outputBufferId = codec.dequeueOutputBuffer(info, 10_000);
                if (outputBufferId >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferId);
                    while (outputBuffer.remaining() > 0) {
                        int read = Math.min(outputBuffer.remaining(), buffer.length);
                        outputBuffer.get(buffer, 0, read);
                        out.write(buffer, 0, read);
                    }
                    codec.releaseOutputBuffer(outputBufferId, false);
                    presentationTime = TimeUnit.MICROSECONDS.toMillis(info.presentationTimeUs);
                    return info.size;
                } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Log.d(TAG, "dequeueOutputBuffer: try again later");
                } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Output format changed: " + codec.getOutputFormat());
                } else {
                    Log.e(TAG, "Unexpected dequeueOutputBuffer result: " + outputBufferId);
                    return -1;
                }
            }
        }
    }

    @Override
    public void seek(int positionMs) {
        extractor.seekTo(TimeUnit.MILLISECONDS.toMicros(positionMs), MediaExtractor.SEEK_TO_CLOSEST_SYNC);
    }

    @Override
    public void close() throws IOException {
        synchronized (closeLock) {
            codec.release();
            extractor.release();
            super.close();
        }
    }

    @Override
    public int time() {
        return (int) presentationTime;
    }
}
