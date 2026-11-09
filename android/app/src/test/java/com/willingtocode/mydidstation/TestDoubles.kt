package com.willingtocode.mydidstation

import android.util.Base64

/**
 * A stand-in for the Keystore, for tests that need a whole `Container`.
 *
 * Robolectric has no `AndroidKeyStore` provider, so the real box cannot run on the JVM. This
 * one is opaque to a grep and nothing more, which is all a screen test needs: it is not
 * pretending to be secure, and nothing should read it as a claim that it is.
 *
 * The credential contract itself is exercised against a fake with more levers in
 * `SettingsTest`, which is the file that asserts what happens when a value will not open.
 */
internal class OpaqueBox : SecretBox {

    override fun seal(purpose: String, plain: String): String =
        Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)

    override fun open(purpose: String, sealed: String): Unsealed {
        val plain = runCatching { String(Base64.decode(sealed, Base64.NO_WRAP)) }.getOrNull()
        return if (plain == null) Unsealed.Unreadable else Unsealed.Ok(plain)
    }
}
