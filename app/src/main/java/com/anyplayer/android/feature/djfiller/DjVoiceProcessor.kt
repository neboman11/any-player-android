package com.anyplayer.android.feature.djfiller

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Broadcast-style finishing chain for raw TTS output: trims dead air, shapes the voice with
 *  EQ, evens it out with a compressor, normalizes it to [TARGET_LUFS] (ITU-R BS.1770
 *  integrated loudness) so every break sits at the same level as streaming-normalized music,
 *  applies the user's level trim, then catches peaks with a lookahead limiter instead of
 *  hard clipping. */
internal object DjVoiceProcessor {
    const val TARGET_LUFS = -14.0
    const val CEILING_DB = -1.0
    private const val SILENCE_DB = -50.0

    fun process(input: FloatArray, sampleRate: Int, level: Float = 1f): FloatArray {
        val trimmed = trimSilence(input, sampleRate)
        if (trimmed.isEmpty()) return trimmed
        val x = DoubleArray(trimmed.size) { trimmed[it].toDouble() }
        val fs = sampleRate.toDouble()
        // Rumble/DC out, a little warmth, less boxiness, more presence and air.
        Biquad.highPass(fs, 80.0, 0.707).run(x)
        Biquad.lowShelf(fs, 150.0, 0.707, 1.5).run(x)
        Biquad.peaking(fs, 300.0, 1.0, -2.5).run(x)
        Biquad.peaking(fs, 3500.0, 0.9, 3.0).run(x)
        if (fs > 20_000) Biquad.highShelf(fs, 8000.0, 0.707, 2.0).run(x)
        compress(x, fs, thresholdDb = -20.0, ratio = 3.0, kneeDb = 6.0, attackMs = 5.0, releaseMs = 120.0)
        // Limiting peaky speech lowers its loudness, so re-measure and top up; the limiter
        // only ever pulls peaks down, and each pass converges closer to the target.
        val target = TARGET_LUFS + 20 * log10(level.toDouble())
        repeat(3) {
            val loudness = integratedLoudness(x, fs)
            if (!loudness.isFinite()) return@repeat
            val gain = dbToLinear(target - loudness)
            for (i in x.indices) x[i] *= gain
            limit(x, fs, dbToLinear(CEILING_DB), lookaheadMs = 3.0, releaseMs = 80.0)
        }
        return padAndFade(x, sampleRate)
    }

    /** ITU-R BS.1770-4 gated integrated loudness in LUFS; -Infinity for silence. */
    fun integratedLoudness(samples: DoubleArray, fs: Double): Double {
        val z = samples.copyOf()
        // K-weighting (pre-filter shelf + RLB high-pass) as RBJ biquads, matching
        // pyloudnorm's any-sample-rate approximation of the standard's 48 kHz coefficients.
        Biquad.highShelf(fs, 1500.0, 0.7071067811865475, 4.0).run(z)
        Biquad.highPass(fs, 38.0, 0.5).run(z)
        val block = (0.4 * fs).toInt()
        val step = (0.1 * fs).toInt()
        val powers = if (z.size < block) {
            listOf(meanSquare(z, 0, z.size))
        } else {
            (0..(z.size - block) step step).map { meanSquare(z, it, block) }
        }
        val absolute = powers.filter { blockLoudness(it) > -70.0 }
        if (absolute.isEmpty()) return Double.NEGATIVE_INFINITY
        val relativeGate = blockLoudness(absolute.average()) - 10.0
        return blockLoudness(absolute.filter { blockLoudness(it) > relativeGate }.average())
    }

    private fun meanSquare(z: DoubleArray, from: Int, count: Int): Double {
        var sum = 0.0
        for (i in from until from + count) sum += z[i] * z[i]
        return sum / count
    }

    private fun blockLoudness(power: Double): Double = -0.691 + 10 * log10(power)

    private fun trimSilence(input: FloatArray, sampleRate: Int): FloatArray {
        val threshold = dbToLinear(SILENCE_DB).toFloat()
        val first = input.indexOfFirst { abs(it) > threshold }
        if (first < 0) return FloatArray(0)
        val last = input.indexOfLast { abs(it) > threshold }
        val margin = sampleRate / 50
        return input.copyOfRange(max(0, first - margin), min(input.size, last + margin + 1))
    }

    /** Feed-forward compressor: peak envelope follower driving a soft-knee gain computer. */
    private fun compress(
        x: DoubleArray, fs: Double, thresholdDb: Double, ratio: Double, kneeDb: Double,
        attackMs: Double, releaseMs: Double
    ) {
        val attack = exp(-1.0 / (attackMs / 1000 * fs))
        val release = exp(-1.0 / (releaseMs / 1000 * fs))
        var envelope = 0.0
        for (i in x.indices) {
            val rectified = abs(x[i])
            val coefficient = if (rectified > envelope) attack else release
            envelope = coefficient * envelope + (1 - coefficient) * rectified
            val over = 20 * log10(max(envelope, 1e-9)) - thresholdDb
            val reductionDb = when {
                2 * over <= -kneeDb -> 0.0
                2 * abs(over) < kneeDb -> (1 / ratio - 1) * (over + kneeDb / 2).pow(2) / (2 * kneeDb)
                else -> (1 / ratio - 1) * over
            }
            x[i] *= dbToLinear(reductionDb)
        }
    }

    /** Lookahead peak limiter. Each sample's required gain is min-filtered forward over the
     *  lookahead window, then averaged over the trailing window: every averaged value comes
     *  from windows that all cover the current sample, so the smoothed gain ramps down ahead
     *  of a peak yet never exceeds what that peak needs. Recovery uses a one-pole release,
     *  which only ever lags below the target, so it stays safe too. */
    private fun limit(x: DoubleArray, fs: Double, ceiling: Double, lookaheadMs: Double, releaseMs: Double) {
        val window = max(1, (lookaheadMs / 1000 * fs).toInt())
        val needed = DoubleArray(x.size) { min(1.0, ceiling / max(abs(x[it]), 1e-12)) }
        val minimum = DoubleArray(x.size) { i ->
            var m = 1.0
            for (k in i until min(x.size, i + window)) m = min(m, needed[k])
            m
        }
        val release = exp(-1.0 / (releaseMs / 1000 * fs))
        var running = 0.0
        var gain = 1.0
        for (i in x.indices) {
            running += minimum[i]
            if (i >= window) running -= minimum[i - window]
            val averaged = running / min(i + 1, window)
            gain = if (averaged < gain) averaged else release * gain + (1 - release) * averaged
            x[i] *= gain
        }
    }

    /** Short lead-in and tail so the voice doesn't butt against the songs, with fades that
     *  keep the trimmed edges click-free. */
    private fun padAndFade(x: DoubleArray, sampleRate: Int): FloatArray {
        val lead = sampleRate * 50 / 1000
        val tail = sampleRate * 200 / 1000
        val fadeIn = max(1, min(x.size, sampleRate * 10 / 1000))
        val fadeOut = max(1, min(x.size, sampleRate * 40 / 1000))
        val out = FloatArray(lead + x.size + tail)
        for (i in x.indices) {
            val fromEnd = x.size - 1 - i
            val fade = min(1.0, min(i.toDouble() / fadeIn, fromEnd.toDouble() / fadeOut))
            out[lead + i] = (x[i] * fade).toFloat()
        }
        return out
    }

    private fun dbToLinear(db: Double): Double = 10.0.pow(db / 20)

    /** RBJ Audio EQ Cookbook biquad, transposed direct form II. */
    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double
    ) {
        fun run(x: DoubleArray) {
            var s1 = 0.0
            var s2 = 0.0
            for (i in x.indices) {
                val input = x[i]
                val output = b0 * input + s1
                s1 = b1 * input - a1 * output + s2
                s2 = b2 * input - a2 * output
                x[i] = output
            }
        }

        companion object {
            private fun normalized(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
                Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

            fun highPass(fs: Double, f0: Double, q: Double): Biquad {
                val w = 2 * PI * f0 / fs
                val alpha = sin(w) / (2 * q)
                val c = cos(w)
                return normalized((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }

            fun peaking(fs: Double, f0: Double, q: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40)
                val w = 2 * PI * f0 / fs
                val alpha = sin(w) / (2 * q)
                val c = cos(w)
                return normalized(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
            }

            fun lowShelf(fs: Double, f0: Double, q: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40)
                val w = 2 * PI * f0 / fs
                val k = 2 * sqrt(a) * sin(w) / (2 * q)
                val c = cos(w)
                return normalized(
                    a * ((a + 1) - (a - 1) * c + k), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - k),
                    (a + 1) + (a - 1) * c + k, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - k
                )
            }

            fun highShelf(fs: Double, f0: Double, q: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40)
                val w = 2 * PI * f0 / fs
                val k = 2 * sqrt(a) * sin(w) / (2 * q)
                val c = cos(w)
                return normalized(
                    a * ((a + 1) + (a - 1) * c + k), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - k),
                    (a + 1) - (a - 1) * c + k, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - k
                )
            }
        }
    }
}
