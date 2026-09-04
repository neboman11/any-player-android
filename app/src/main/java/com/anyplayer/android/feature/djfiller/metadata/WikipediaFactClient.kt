package com.anyplayer.android.feature.djfiller.metadata

import com.anyplayer.android.core.log.CompatLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Looks up a single short factual blurb about an artist to feed into the DJ script
 *  prompt, via Wikipedia's no-key REST summary endpoint. One call per DJ break, never
 *  retried: any failure (404, timeout, malformed response) just means the script
 *  generates without a fact rather than delaying or blocking the break. */
@Singleton
class WikipediaFactClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    private companion object {
        const val TAG = "WikipediaFactClient"
        const val TIMEOUT_SECONDS = 2L
    }

    // Short, fixed timeout so a slow/unreachable Wikipedia never eats into the
    // generation-ahead-of-time budget the scheduler is counting on.
    private val shortTimeoutClient = okHttpClient.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun fetchArtistFact(artistName: String): String? = withContext(Dispatchers.IO) {
        val trimmed = artistName.trim()
        if (trimmed.isEmpty()) return@withContext null

        val encoded = URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
        val request = Request.Builder()
            .url("https://en.wikipedia.org/api/rest_v1/page/summary/$encoded")
            .header("Accept", "application/json")
            .get()
            .build()

        runCatching {
            shortTimeoutClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val extract = json.parseToJsonElement(body).jsonObject["extract"]
                    ?.jsonPrimitive?.content?.trim()
                extract?.takeIf { it.isNotEmpty() }
            }
        }.onFailure {
            CompatLog.d(TAG, "artist fact lookup failed for '$trimmed': ${it.message}")
        }.getOrNull()
    }
}
