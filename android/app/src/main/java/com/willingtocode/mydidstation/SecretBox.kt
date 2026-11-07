package com.willingtocode.mydidstation

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts one short string so it can sit in a preference file.
 *
 * An interface because the real one needs the Android Keystore, and Robolectric has no
 * `AndroidKeyStore` provider, so nothing that depends on it would be testable.
 */
interface SecretBox {

    /** Returns the ciphertext, encoded for storage. */
    fun seal(plain: String): String

    /** Returns null when the value is damaged or was sealed under a key that is gone. */
    fun open(sealed: String): String?
}

/**
 * AES-256-GCM with a key that never leaves the Android Keystore.
 *
 * The key is created on first use and lives in the TEE, so a dumped process cannot read
 * it (BACKLOG.md section 6). StrongBox is deliberately not requested: it is a separate
 * chip with a handful of key slots, it cannot run on an emulator, and what it defends
 * against is a TEE exploit, which is not this app's threat model.
 *
 * The IV comes from the Keystore rather than from this code, because GCM IV reuse is
 * catastrophic and `setRandomizedEncryptionRequired` is the default that prevents it.
 * Layout is `version || ivLength || iv || ciphertext`, then base64.
 */
class KeystoreBox : SecretBox {

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(2 + iv.size + body.size)
        out[0] = VERSION
        out[1] = iv.size.toByte()
        iv.copyInto(out, 2)
        body.copyInto(out, 2 + iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    override fun open(sealed: String): String? = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        if (raw.size < 2 + MIN_IV_BYTES + TAG_BYTES || raw[0] != VERSION) {
            null
        } else {
            val ivSize = raw[1].toInt()
            if (ivSize <= 0 || raw.size < 2 + ivSize + TAG_BYTES) {
                null
            } else {
                val iv = raw.copyOfRange(2, 2 + ivSize)
                val body = raw.copyOfRange(2 + ivSize, raw.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
                String(cipher.doFinal(body), Charsets.UTF_8)
            }
        }
    } catch (_: Exception) {
        // Damaged, sealed under another key, or the key was invalidated by the system.
        // All three mean "no credentials", and none of them may crash a poll.
        null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        existing(store)?.let { return it }
        // A half-created or invalidated entry would block generation, so clear it first.
        runCatching { store.deleteEntry(ALIAS) }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun existing(store: KeyStore): SecretKey? = try {
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "mydidstation_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val MIN_IV_BYTES = 12
        const val VERSION: Byte = 1
    }
}
