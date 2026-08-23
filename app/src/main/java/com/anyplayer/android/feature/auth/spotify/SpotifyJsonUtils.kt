package com.anyplayer.android.feature.auth.spotify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal val JsonElement?.jsonObject: JsonObject
    get() = this as? JsonObject ?: JsonObject(emptyMap())

internal val JsonElement?.jsonPrimitiveStringOrNull: String?
    get() = this?.jsonPrimitive?.contentOrNull

internal val JsonElement?.jsonPrimitiveStringOrEmpty: String
    get() = this?.jsonPrimitive?.contentOrNull.orEmpty()

internal val JsonElement?.jsonPrimitiveIntOrZero: Int
    get() = this?.jsonPrimitive?.intOrNull ?: 0

internal val JsonElement?.jsonPrimitiveBooleanOrFalse: Boolean
    get() = this?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

private const val PREFERRED_IMAGE_WIDTH = 300

internal fun joinArtistNames(artists: JsonArray?): String {
    val names = artists.orEmpty()
        .mapNotNull { it.jsonObject["name"].jsonPrimitiveStringOrNull }
        .filter { it.isNotBlank() }
    return if (names.isEmpty()) "Unknown Artist" else names.joinToString(", ")
}

internal fun bestImageUrl(images: JsonArray?): String? {
    if (images == null || images.isEmpty()) return null
    var best = images.first().jsonObject
    var bestDiff = Int.MAX_VALUE
    for (img in images) {
        val w = img.jsonObject["width"]?.jsonPrimitive?.intOrNull ?: continue
        val diff = kotlin.math.abs(w - PREFERRED_IMAGE_WIDTH)
        if (diff < bestDiff) {
            bestDiff = diff
            best = img.jsonObject
        }
    }
    return best["url"].jsonPrimitiveStringOrNull
}
