package com.anyplayer.android.data.transfer

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class StateTransferCrypto {
    private val random = SecureRandom()
    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun encrypt(plainText: String, passphrase: String, iterations: Int = 150_000): EncryptedStateFile {
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveKey(passphrase, salt, iterations), "AES"), GCMParameterSpec(128, nonce))
        return EncryptedStateFile(kdfIterations = iterations,
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            nonceBase64 = Base64.getEncoder().encodeToString(nonce),
            ciphertextBase64 = Base64.getEncoder().encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))))
    }

    fun decrypt(file: EncryptedStateFile, passphrase: String): String {
        val salt = Base64.getDecoder().decode(file.saltBase64)
        val nonce = Base64.getDecoder().decode(file.nonceBase64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(passphrase, salt, file.kdfIterations), "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(Base64.getDecoder().decode(file.ciphertextBase64)).toString(Charsets.UTF_8)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}
