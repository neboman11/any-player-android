package com.anyplayer.android.feature.djfiller

import com.anyplayer.android.core.network.normalizeSyncServerAuthToken
import com.anyplayer.android.core.network.normalizeSyncServerBaseUrl
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.security.MessageDigest

/** Shared HTTP plumbing for [DjModelManager] (single LLM model file) and
 *  [VoiceModelDownloader] (voice catalog + zip bundles) - both download from the same
 *  user-configured sync server and previously duplicated this boilerplate.
 *  [normalizeSyncServerBaseUrl]/[normalizeSyncServerAuthToken] live in core/network since
 *  [com.anyplayer.android.feature.sync.SyncSnapshotClient] needs the identical normalization
 *  too; re-exported here so existing call sites in this package don't need their own import. */

/** Both [DjModelManager] and [VoiceModelDownloader] build a file path from a server-supplied
 *  version/id string, so it must be rejected if it could escape the target directory (e.g. a
 *  path-traversal segment from a compromised/MITM'd sync server). */
internal val SAFE_COMPONENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

/** A lowercase/uppercase-agnostic SHA-256 hex digest, as returned by both download-info
 *  endpoints - used to require (not just optionally check) an integrity digest before a
 *  downloaded file is trusted. */
internal val SHA_256 = Regex("[0-9a-fA-F]{64}")

/** Shared byte-array-to-lowercase-hex helper for the SHA-256 digests computed across the
 *  download/verification paths in this package (model download, voice bundle download, and
 *  espeak-data asset hashing) - previously hand-copied at each call site. */
internal fun ByteArray.toHexDigest(): String = joinToString("") { "%02x".format(it) }

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
        val expectedBytes = expectedSize.takeIf { it > 0 }
        var bytesRead = 0L
        outputFile.outputStream().use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    check(expectedBytes == null || bytesRead + read <= expectedBytes) {
                        "download exceeded advertised size"
                    }
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    bytesRead += read
                    if (totalBytes > 0) {
                        onProgress(bytesRead.toFloat() / totalBytes)
                    }
                }
            }
        }
        check(expectedBytes == null || bytesRead == expectedBytes) {
            "download did not match advertised size"
        }
    }
}
