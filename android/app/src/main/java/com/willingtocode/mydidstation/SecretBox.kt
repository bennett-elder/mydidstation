package com.willingtocode.mydidstation

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What came back from [SecretBox.open].
 *
 * Four outcomes, not a nullable string, because "the value is bad" and "the Keystore is
 * not answering" call for opposite responses. Collapsing them means a device whose
 * Keystore is briefly unavailable gets its credentials deleted.
 */
sealed interface Unsealed {

    /** The value, decrypted. */
    data class Ok(val value: String) : Unsealed

    /** Nothing is stored under this purpose. */
    data object Missing : Unsealed

    /**
     * The value exists and cannot be opened: damaged, tampered with, sealed for another
     * purpose, or sealed under a key this device no longer holds. Retrying will not help,
     * so the caller may drop it.
     */
    data object Unreadable : Unsealed

    /**
     * The Keystore could not be reached, which says nothing about the value. The caller
     * must keep it and try again later.
     */
    data object Unavailable : Unsealed
}

/**
 * Encrypts one short string so it can sit in a preference file.
 *
 * An interface because the real one needs the Android Keystore, and Robolectric has no
 * `AndroidKeyStore` provider, so nothing that depends on it would be testable.
 */
interface SecretBox {

    /**
     * Returns the ciphertext, encoded for storage. [purpose] is authenticated with the
     * value, so a blob sealed for one use cannot be opened as another.
     */
    fun seal(purpose: String, plain: String): String

    /** Opens a value sealed for [purpose]. See [Unsealed] for the four outcomes. */
    fun open(purpose: String, sealed: String): Unsealed
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
 * That default is written out at the key, so a later edit cannot quietly drop it.
 * Layout is `version || ivLength || iv || ciphertext`, then base64, and [purpose] is
 * authenticated as associated data.
 */
class KeystoreBox : SecretBox {

    override fun seal(purpose: String, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(2 + iv.size + body.size)
        out[0] = VERSION
        out[1] = iv.size.toByte()
        iv.copyInto(out, 2)
        body.copyInto(out, 2 + iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    override fun open(purpose: String, sealed: String): Unsealed {
        val raw = runCatching { Base64.decode(sealed, Base64.NO_WRAP) }.getOrNull()
            ?: return Unsealed.Unreadable
        if (raw.size < 2 + MIN_IV_BYTES + TAG_BYTES || raw[0] != VERSION) {
            return Unsealed.Unreadable
        }
        val ivSize = raw[1].toInt()
        if (ivSize <= 0 || raw.size < 2 + ivSize + TAG_BYTES) return Unsealed.Unreadable
        val iv = raw.copyOfRange(2, 2 + ivSize)
        val body = raw.copyOfRange(2 + ivSize, raw.size)

        val key = runCatching { key() }.getOrElse {
            // No usable Keystore, which says nothing about the stored value.
            return Unsealed.Unavailable
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
            Unsealed.Ok(String(cipher.doFinal(body), Charsets.UTF_8))
        } catch (_: AEADBadTagException) {
            // Tampered, sealed for another purpose, or a key that no longer matches.
            Unsealed.Unreadable
        } catch (_: KeyPermanentlyInvalidatedException) {
            Unsealed.Unreadable
        } catch (_: Exception) {
            // The key is there but the operation could not run. Not the value's fault.
            Unsealed.Unavailable
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        existing(store)?.let { return it }
        return generate(store)
    }

    /**
     * Creates the key. An entry that exists but cannot be used blocks generation, so it is
     * cleared once and the attempt repeated. Nothing is deleted unless an alias is really
     * there: a Keystore that was merely unreachable must not cost the user credentials.
     */
    private fun generate(store: KeyStore): SecretKey = try {
        generator().generateKey()
    } catch (first: Exception) {
        if (!store.containsAlias(ALIAS)) throw first
        store.deleteEntry(ALIAS)
        generator().generateKey()
    }

    private fun generator(): KeyGenerator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    // The default, stated so it cannot be lost: the Keystore picks the IV
                    // and refuses a caller-supplied one, which is what makes GCM IV reuse
                    // impossible from this code.
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
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
