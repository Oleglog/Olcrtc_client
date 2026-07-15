package io.github.oleglog.olcrtc.client.data

import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SecretCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = SecretCipher { key }

    @Test
    fun roundTripsSecret() {
        assertEquals("secret", cipher.decrypt(cipher.encrypt("secret")))
    }

    @Test
    fun usesFreshNonceForEachEncryption() {
        val first = cipher.encrypt("secret")
        val second = cipher.encrypt("secret")

        assertFalse(first.contentEquals(second))
        assertArrayEquals("secret".encodeToByteArray(), cipher.decrypt(first).encodeToByteArray())
        assertArrayEquals("secret".encodeToByteArray(), cipher.decrypt(second).encodeToByteArray())
    }

    @Test
    fun rejectsModifiedCiphertext() {
        val encrypted = cipher.encrypt("secret")
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()

        assertThrows(Exception::class.java) { cipher.decrypt(encrypted) }
    }
}
