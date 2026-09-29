package com.anyplayer.android.feature.djfiller.metadata

import com.anyplayer.android.core.log.CompatLog
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** The passage lookup and played endpoints are idempotent, so a brief 503 can be retried. */
internal suspend fun OkHttpClient.executeDjServerRequest(request: Request): Response {
    repeat(2) { retry ->
        val response = newCall(request).execute()
        if (response.code != 503) return response
        response.close()
        CompatLog.w("DjPassageHttp", "AI DJ request returned HTTP 503; retrying (${retry + 1}/2)")
        delay(250L shl retry)
    }
    return newCall(request).execute()
}
