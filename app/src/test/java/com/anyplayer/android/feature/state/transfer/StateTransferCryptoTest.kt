package com.anyplayer.android.feature.state.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StateTransferCryptoTest {
    private val crypto = StateTransferCrypto()

    @Test
    fun encryptDecrypt_roundTripsPayload() {
        val original = "{\"format\":\"any-player-state\",\"version\":1}"
        val encrypted = crypto.encrypt(original, passphrase = "secret-pass")

        val decrypted = crypto.decrypt(encrypted, passphrase = "secret-pass")

        assertEquals(original, decrypted)
    }

    @Test
    fun sha256_changesWhenPayloadChanges() {
        val hash1 = crypto.sha256("alpha")
        val hash2 = crypto.sha256("beta")

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun decrypt_withWrongPassphrase_throws() {
        val encrypted = crypto.encrypt("payload", passphrase = "correct-pass")

        assertThrows(Exception::class.java) {
            crypto.decrypt(encrypted, passphrase = "wrong-pass")
        }
    }

    @Test
    fun decrypt_withTamperedCiphertext_throws() {
        val encrypted = crypto.encrypt("payload", passphrase = "pass")
        val tampered = encrypted.copy(ciphertextBase64 = encrypted.ciphertextBase64.reversed())

        assertThrows(Exception::class.java) {
            crypto.decrypt(tampered, passphrase = "pass")
        }
    }
}
