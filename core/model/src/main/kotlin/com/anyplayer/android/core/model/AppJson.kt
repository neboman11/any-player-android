package com.anyplayer.android.core.model

import kotlinx.serialization.json.Json

/**
 * The single JSON configuration shared by Room converters, sync and export. Identical to the
 * legacy instance (`encodeDefaults = false`) so export integrity hashes reproduce byte-for-byte.
 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
