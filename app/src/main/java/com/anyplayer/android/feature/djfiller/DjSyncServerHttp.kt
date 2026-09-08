package com.anyplayer.android.feature.djfiller

import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.security.MessageDigest

/** Shared HTTP plumbing for [DjModelManager] (single LLM model file) and
 *  [VoiceModelDownloader] (voice catalog + zip bundles) - both download from the same
 *  user-configured sync server and previously duplicated this boilerplate. */

internal fun normalizeSyncServerBaseUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    return when {
        trimmed.isBlank() -> trimmed
        trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
        else -> "https://$trimmed"
    }
}

internal fun normalizeSyncServerAuthToken(raw: String): String =
    raw.trim().replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "")

internal fun authorizedSyncServerRequest(url: String, token: String): Request = Request.Builder()
    .url(url)
    .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
    .get()
    .build()

/** A non-2xx HTTP response is not the same failure as a real network/connection error, and
 *  the two need different fixes from the user - lumping them into one generic "could not
 *  reach server" message was actively misleading (a 404 here means the server was reached
 *  fine, it just has nothing configured for this endpoint). [notConfiguredMessage] fills in
 *  the specific "nothing configured" wording for the 404 case, which differs by endpoint. */
internal fun describeFailedSyncResponse(response: Response?, exception: Throwable?, notConfiguredMessage: String): String {
    if (response == null) {
        return "Could not reach the sync server. Check the Sync Server Target and your network connection." +
            (exception?.message?.let { " ($it)" } ?: "")
    }
    return when (response.code) {
        401, 403 -> "Sync server rejected the auth token. Check the Sync Auth Token setting."
        404 -> notConfiguredMessage
        else -> "Sync server returned an error (HTTP ${response.code})."
    }
}

/** Streams [response]'s body to [outputFile] in 64KB chunks, feeding [digest] and reporting
 *  fractional progress via [onProgress] (skipped if [expectedSize] and the response's own
 *  content length are both unknown). Caller is responsible for closing [response] (this
 *  function does, via `.use`) and for wrapping the call in `runCatching`/try-catch. */
internal fun downloadSyncServerResponseToFile(
    response: Response,
    outputFile: File,
    expectedSize: Long,
    digest: MessageDigest,
    onProgress: (Float) -> Unit
) {
    response.use { result ->
        val body = result.body ?: error("empty response body")
        val totalBytes = expectedSize.takeIf { it > 0 } ?: body.contentLength()
        var bytesRead = 0L
        outputFile.outputStream().use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    bytesRead += read
                    if (totalBytes > 0) {
                        onProgress(bytesRead.toFloat() / totalBytes)
                    }
                }
            }
        }
    }
}
