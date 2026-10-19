package com.anyplayer.android.core.model

/**
 * Port of the shared Rust core's `effective_output_volume` with the internal target 85.
 * A per-source volume scalar, not loudness analysis. Uses Float math like the Rust `f32` code.
 */
object VolumeNormalization {
    const val TARGET: Int = 85

    /** `clamp((0.04 + 0.85 * 0.18) / 0.22, 0.1, 1.0)` computed in `f32`. */
    val OTHER_SOURCE_FACTOR: Float = run {
        val normalized = TARGET.toFloat() / 100.0f
        val targetRms = 0.04f + normalized * 0.18f
        val maxTargetRms = 0.04f + 0.18f
        (targetRms / maxTargetRms).coerceIn(0.1f, 1.0f)
    }

    fun effective(volume: Int, source: SourceType, enabled: Boolean): Int {
        val base = volume.coerceIn(0, 100)
        if (!enabled) return base
        return if (source == SourceType.SPOTIFY) {
            ((base * TARGET + 50) / 100).coerceAtMost(100)
        } else {
            Math.round(base.toFloat() * OTHER_SOURCE_FACTOR).coerceIn(0, 100)
        }
    }
}
