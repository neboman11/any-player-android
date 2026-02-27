package com.anyplayer.android.feature.startup

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StartupResilienceManager @Inject constructor(
    private val authRepository: ProviderAuthRepository,
    private val providerCatalogRepository: StartupCatalogGateway
) {
    suspend fun runStartup(
        continueWithoutProviders: Boolean,
        onProgress: (String) -> Unit
    ): StartupSnapshot {
        val warnings = mutableListOf<String>()

        onProgress("Loading cached playlists...")
        val cachedPlaylists = providerCatalogRepository.getCachedProviderPlaylists()

        if (continueWithoutProviders) {
            onProgress("Startup complete using cached/local data.")
            return StartupSnapshot(
                providerStatuses = emptyList(),
                providerPlaylists = cachedPlaylists,
                warnings = warnings,
                usedFallback = true
            )
        }

        onProgress("Restoring provider sessions...")
        val statuses = retryWithBackoff(attempts = 2, initialDelayMs = 500) {
            withTimeoutOrNull(8_000) {
                authRepository.restoreAll()
            } ?: throw IllegalStateException("Provider session restore timed out")
        }.getOrElse { error ->
            warnings += "Session restore failed: ${error.message}"
            emptyList()
        }

        onProgress("Loading provider playlists...")
        val playlists = retryWithBackoff(attempts = 3, initialDelayMs = 700) {
            withTimeoutOrNull(10_000) {
                providerCatalogRepository.getAllProviderPlaylistsWithCache()
            } ?: throw IllegalStateException("Provider playlist refresh timed out")
        }.getOrElse { error ->
            warnings += "Playlist refresh failed: ${error.message}"
            cachedPlaylists
        }

        onProgress(
            if (statuses.isEmpty()) {
                "Startup complete. No providers restored; running with local/cached data."
            } else {
                "Startup complete. ${statuses.size} provider session(s) restored."
            }
        )

        return StartupSnapshot(
            providerStatuses = statuses,
            providerPlaylists = playlists,
            warnings = warnings,
            usedFallback = statuses.isEmpty() || warnings.isNotEmpty()
        )
    }

    private suspend fun <T> retryWithBackoff(
        attempts: Int,
        initialDelayMs: Long,
        block: suspend () -> T
    ): Result<T> {
        var delayMs = initialDelayMs
        repeat(attempts - 1) {
            val attempt = runCatching { block() }
            if (attempt.isSuccess) {
                return attempt
            }
            delay(delayMs)
            delayMs *= 2
        }
        return runCatching { block() }
    }
}

data class StartupSnapshot(
    val providerStatuses: List<ProviderConnectionProfile>,
    val providerPlaylists: List<Playlist>,
    val warnings: List<String>,
    val usedFallback: Boolean
)
