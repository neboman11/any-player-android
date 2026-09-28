package com.anyplayer.android.feature.djfiller

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjVoiceProcessorTest {
    @Test
    fun `integrated loudness matches the BS1770 full scale sine reference`() {
        for (fs in listOf(24_000, 48_000)) {
            val sine = DoubleArray(fs * 5) { sin(2 * PI * 997 * it / fs) }
            assertEquals(-3.01, DjVoiceProcessor.integratedLoudness(sine, fs.toDouble()), 0.1)
        }
    }

    @Test
    fun `speech-like input comes out at target loudness under the ceiling with clean edges`() {
        val fs = 24_000
        for (inputScale in listOf(0.02, 0.9, 4.0)) {
            val output = DjVoiceProcessor.process(syntheticSpeech(fs, inputScale), fs)
            val peak = output.maxOf { abs(it) }
            assertTrue("peak $peak exceeds ceiling", peak <= 10.0.pow(DjVoiceProcessor.CEILING_DB / 20) + 1e-6)
            val loudness = DjVoiceProcessor.integratedLoudness(DoubleArray(output.size) { output[it].toDouble() }, fs.toDouble())
            assertEquals("input scale $inputScale", DjVoiceProcessor.TARGET_LUFS, loudness, 1.5)
            assertEquals(0f, output.first(), 0f)
            assertEquals(0f, output.last(), 0f)
        }
    }

    @Test
    fun `level trim shifts loudness and silence stays empty`() {
        val fs = 24_000
        val quieter = DjVoiceProcessor.process(syntheticSpeech(fs, 0.5), fs, level = 0.5f)
        val loudness = DjVoiceProcessor.integratedLoudness(DoubleArray(quieter.size) { quieter[it].toDouble() }, fs.toDouble())
        assertEquals(DjVoiceProcessor.TARGET_LUFS - 6.0, loudness, 1.0)
        assertTrue(DjVoiceProcessor.process(FloatArray(fs), fs).isEmpty())
    }

    /** Voiced harmonic syllables with noisy consonants and pauses, bracketed by silence. */
    private fun syntheticSpeech(fs: Int, scale: Double): FloatArray {
        val random = Random(7)
        val samples = FloatArray(fs * 4)
        var t = fs / 4
        while (t < samples.size - fs / 4) {
            val length = fs / 5 + random.nextInt(fs / 10)
            val f0 = 110.0 + random.nextInt(40)
            for (i in 0 until length) {
                val envelope = sin(PI * i / length)
                val voiced = (1..8).sumOf { h -> sin(2 * PI * f0 * h * i / fs) / h }
                val consonant = if (i < length / 8) random.nextDouble(-0.4, 0.4) else 0.0
                samples[t + i] = (scale * 0.3 * envelope * (voiced + consonant)).toFloat()
            }
            t += length + fs / 20 + random.nextInt(fs / 10)
        }
        return samples
    }
}
