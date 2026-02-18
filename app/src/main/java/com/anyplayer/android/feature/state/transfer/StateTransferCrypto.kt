package com.anyplayer.android.feature.state.transfer

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
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
            saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP),
            nonceBase64 = Base64.encodeToString(nonce, Base64.NO_WRAP),
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        )
    }

    fun decrypt(file: EncryptedStateFile, passphrase: String): String {
        val salt = Base64.decode(file.saltBase64, Base64.NO_WRAP)
        val nonce = Base64.decode(file.nonceBase64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(file.ciphertextBase64, Base64.NO_WRAP)
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
