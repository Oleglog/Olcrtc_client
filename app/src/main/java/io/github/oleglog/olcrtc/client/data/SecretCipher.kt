package io.github.oleglog.olcrtc.client.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Storage model (issue #49 audit, 2026-09-06):
 * - Encrypted with this key: roomPassword, keyHex, standard-profile secret
 *   blobs, subscription URLs / mirror URLs / mirror keys, subscription
 *   config JSON. The wbstream auth token was removed from the client
 *   entirely (commit 25302ec), so there is no token left to protect.
 * - Plaintext by design (identifiers needed for lists and display):
 *   roomId, clientId, names, endpoints, DNS strings.
 * - Auto Backup is fully disabled (allowBackup=false) and every storage
 *   domain is excluded in res/xml/data_extraction_rules.xml, so the
 *   database never leaves the device via backup or device transfer.
 * - The key prefers StrongBox hardware backing and falls back to the
 *   default (TEE/software) provider when StrongBox is unavailable.
 */
internal class SecretCipher(
    private val keyProvider: () -> SecretKey = ::loadOrCreateKey,
) {
    fun encrypt(value: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        return ByteBuffer.allocate(1 + cipher.iv.size + ciphertext.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(ciphertext)
            .array()
    }

    fun decrypt(value: ByteArray): String {
        val buffer = ByteBuffer.wrap(value)
        val nonce = ByteArray(buffer.get().toInt() and 0xff)
        buffer.get(nonce)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    private companion object {
        const val KEY_ALIAS = "olcrtc-profile-secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun loadOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            return try {
                generator.init(spec(strongBox = true))
                generator.generateKey()
            } catch (error: ProviderException) {
                // Device advertises StrongBox but cannot provision there
                // (e.g. exhausted slots: StrongBoxUnavailableException is a
                // ProviderException subclass). Retry in TEE/software so the
                // app still works. Non-StrongBox failures on old devices
                // are rethrown: the fallback spec is identical there.
                if (!preferStrongBox()) throw error
                generator.init(spec(strongBox = false))
                generator.generateKey()
            }
        }

        private fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(strongBox && preferStrongBox())
            }
            return builder.build()
        }

        private fun preferStrongBox(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    }
}
