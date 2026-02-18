package com.anyplayer.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ProviderConnectionProfile(
    val source: SourceType,
    val connected: Boolean,
    val serverUrl: String? = null,
    val username: String? = null,
    val hasToken: Boolean = false,
    val isPremium: Boolean? = null,
    val playbackReady: Boolean? = null,
    val lastError: String? = null
)
