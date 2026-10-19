package com.anyplayer.android.playback.backend

import com.anyplayer.android.core.model.BackendKind
import dagger.MapKey

/**
 * Hilt map key: each backend module contributes `@Binds @IntoMap @BackendKindKey(BackendKind.X)`
 * so the engine receives `Map<BackendKind, @JvmSuppressWildcards PlaybackBackend>`.
 */
@MapKey
annotation class BackendKindKey(val value: BackendKind)
