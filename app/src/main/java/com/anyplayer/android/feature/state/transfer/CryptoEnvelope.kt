package com.anyplayer.android.feature.state.transfer

import kotlinx.serialization.Serializable

@Serializable
data class EncryptedStateFile(
    val format: String = ANY_PLAYER_STATE_FORMAT,
    val encrypted: Boolean = true,
    val algorithm: String = "AES-256-GCM",
    val kdf: String = "PBKDF2-HMAC-SHA256",
    val kdfIterations: Int,
    val saltBase64: String,
    val nonceBase64: String,
    val ciphertextBase64: String
)
