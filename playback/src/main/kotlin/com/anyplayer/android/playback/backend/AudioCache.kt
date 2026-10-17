package com.anyplayer.android.playback.backend

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.database.StandaloneDatabaseProvider
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.auth.ConnectionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

@Singleton
class AudioCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val connections: ConnectionStore,
) {
    private val mutex = Mutex()
    private var cachedFactory: CacheDataSource.Factory? = null
    private var deviceId = "any-player-android"
    private var version = "dev"
    suspend fun factory(): CacheDataSource.Factory = withContext(Dispatchers.IO) {
        mutex.withLock {
            cachedFactory ?: run {
                deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: deviceId
                version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: version
                val cache = SimpleCache(File(context.cacheDir, "audio_cache"),
                    LeastRecentlyUsedCacheEvictor(500L * 1024 * 1024), StandaloneDatabaseProvider(context))
                val http = OkHttpDataSource.Factory(client)
                val resolving = ResolvingDataSource.Factory(http) { spec ->
                    val connection = connections.connections.value[SourceType.JELLYFIN]
                    val token = connection?.token
                    val request = runCatching { URI(spec.uri.toString()) }.getOrNull()
                    val server = runCatching { URI(connection?.serverUrl ?: "") }.getOrNull()
                    if (!token.isNullOrBlank() && request?.scheme != null &&
                        request.scheme.equals(server?.scheme, true) && request.rawAuthority.equals(server?.rawAuthority, true)) {
                        spec.withRequestHeaders(spec.httpRequestHeaders + mapOf(
                            "X-Emby-Token" to token,
                            "Authorization" to "MediaBrowser Token=\"$token\", Client=\"Any Player\", Device=\"${Build.MODEL.ifBlank { "Android" }}\", DeviceId=\"$deviceId\", Version=\"$version\"",
                        ))
                    } else spec
                }
                CacheDataSource.Factory().setCache(cache)
                    .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, resolving))
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .also { cachedFactory = it }
            }
        }
    }
    suspend fun prefetch(track: Track) {
        val url = track.url ?: return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val factory = factory()
        withContext(Dispatchers.IO) {
            val writer = CacheWriter(factory.createDataSource(), DataSpec.Builder().setUri(url)
                .setKey("${track.source}:${track.id}").setLength(2L * 1024 * 1024).build(), null, null)
            val cancellation = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { writer.cancel() }
            }
            try { writer.cache() } catch (e: Exception) { if (e is CancellationException) throw e }
            finally { cancellation.cancel() }
        }
    }
}
