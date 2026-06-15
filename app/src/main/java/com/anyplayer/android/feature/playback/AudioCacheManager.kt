package com.anyplayer.android.feature.playback

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.anyplayer.android.BuildConfig
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.auth.SecureConnectionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@UnstableApi
class AudioCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureConnectionStore: SecureConnectionStore
) {
    companion object {
        private const val TAG = "AudioCacheManager"
        private const val CACHE_MAX_BYTES = 500L * 1024 * 1024
        private const val PREFETCH_AHEAD_COUNT = 10
        private const val PREFETCH_MAX_BYTES = 512L * 1024
    }

    private val databaseProvider = StandaloneDatabaseProvider(context)
    val simpleCache: SimpleCache = SimpleCache(
        File(context.cacheDir, "audio_cache"),
        LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES),
        databaseProvider
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefetchJob: Job? = null

    /**
     * Data source factory for ExoPlayer.
     * Chain: DefaultDataSource (handles file/content URIs locally)
     *     -> CacheDataSource (serves HTTP from disk cache, or downloads and caches)
     *     -> ResolvingDataSource (injects Jellyfin auth headers)
     *     -> DefaultHttpDataSource (raw HTTP)
     */
    fun buildPlayerDataSourceFactory(): DefaultDataSource.Factory {
        val cacheFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(buildResolvingFactory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DefaultDataSource.Factory(context, cacheFactory)
    }

    /**
     * Cancels any active prefetch and starts downloading [tracks] (up to [PREFETCH_AHEAD_COUNT])
     * into the cache in the background. Skips non-HTTP tracks and anything already cached.
     */
    fun prefetchTracks(tracks: List<Track>) {
        prefetchJob?.cancel()
        if (tracks.isEmpty()) return
        prefetchJob = scope.launch {
            val resolvingFactory = buildResolvingFactory()
            for (track in tracks.take(PREFETCH_AHEAD_COUNT)) {
                ensureActive()
                val url = track.url?.trim().orEmpty()
                if (url.isBlank()) continue
                val uri = runCatching { Uri.parse(url) }.getOrNull() ?: continue
                val scheme = uri.scheme?.lowercase().orEmpty()
                if (scheme !in setOf("http", "https")) continue
                try {
                    val cacheDataSource = CacheDataSource(
                        simpleCache,
                        resolvingFactory.createDataSource(),
                        CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
                    )
                    val dataSpec = DataSpec.Builder()
                        .setUri(uri)
                        .setKey("${track.source}:${track.id}")
                        .setLength(PREFETCH_MAX_BYTES)
                        .build()
                    runInterruptible { CacheWriter(cacheDataSource, dataSpec, null, null).cache() }
                    CompatLog.d(TAG, "Prefetched audio: ${track.title}")
                } catch (e: Exception) {
                    ensureActive()
                    CompatLog.w(TAG, "Prefetch failed for ${track.title}", e)
                }
            }
        }
    }

    fun cancelPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
    }

    private fun buildResolvingFactory(): ResolvingDataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
        return ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            val headers = buildJellyfinRequestHeaders(
                requestUri = dataSpec.uri,
                connection = secureConnectionStore.read(SourceType.JELLYFIN),
                clientName = "Any Player",
                deviceName = Build.MODEL ?: "Android",
                deviceId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )?.takeIf { it.isNotBlank() } ?: "any-player-android",
                version = BuildConfig.VERSION_NAME.ifBlank { "dev" }
            )
            if (headers.isEmpty()) dataSpec else dataSpec.withRequestHeaders(headers)
        }
    }
}
