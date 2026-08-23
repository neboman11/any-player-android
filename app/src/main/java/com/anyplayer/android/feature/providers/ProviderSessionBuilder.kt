package com.anyplayer.android.feature.providers

import com.anyplayer.android.feature.auth.PROVIDER_DEFAULT_PAGE_SIZE

internal object ProviderSessionBuilder {

    fun jellyfinSession(
        url: String,
        apiKey: String,
        userId: String? = null,
        pageSize: Int = PROVIDER_DEFAULT_PAGE_SIZE
    ): Map<String, String> {
        val session = mutableMapOf(
            "url" to url,
            "api_key" to apiKey,
            "page_size" to pageSize.toString()
        )
        if (!userId.isNullOrBlank()) {
            session["user_id"] = userId
        }
        return session
    }

    fun plexSession(
        url: String,
        token: String,
        pageSize: Int = PROVIDER_DEFAULT_PAGE_SIZE
    ): Map<String, String> = mapOf(
        "url" to url,
        "token" to token,
        "page_size" to pageSize.toString()
    )
}
