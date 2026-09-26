package com.anyplayer.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val source: SourceType,
    val url: String? = null,
    val imageUrl: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val enriched: Boolean? = null,
    /** True for the synthetic AI DJ voice-over track spliced between real songs.
     *  Never persisted, never counted as a real song, and shown as "AnyPlayer DJ"
     *  in place of real track metadata wherever the UI/session reads it. */
    val isDjFiller: Boolean = false
)
