package com.anyplayer.android.feature.state.transfer

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class StateTransferCrypto {
    private val secureRandom = SecureRandom()

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    fun encrypt(plainText: String, passphrase: String, iterations: Int = 150_000): EncryptedStateFile {
        val salt = ByteArray(16).also(secureRandom::nextBytes)
        val nonce = ByteArray(12).also(secureRandom::nextBytes)
        val key = deriveKey(passphrase, salt, iterations)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        return EncryptedStateFile(
            kdfIterations = iterations,
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            nonceBase64 = Base64.getEncoder().encodeToString(nonce),
            ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext)
        )
    }

    fun decrypt(file: EncryptedStateFile, passphrase: String): String {
        val salt = Base64.getDecoder().decode(file.saltBase64)
        val nonce = Base64.getDecoder().decode(file.nonceBase64)
        val ciphertext = Base64.getDecoder().decode(file.ciphertextBase64)
        val key = deriveKey(passphrase, salt, file.kdfIterations)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val plainBytes = cipher.doFinal(ciphertext)
        return plainBytes.toString(Charsets.UTF_8)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val keySpec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val secret = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec)
        return secret.encoded
    }
}
