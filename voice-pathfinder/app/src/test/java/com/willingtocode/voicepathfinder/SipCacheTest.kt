package com.willingtocode.voicepathfinder

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The saved SIP credentials, and the invariants that make caching them safe.
 *
 * The cache exists to remove 16 to 26 seconds of provider round trip from a cold start. What
 * these check is not the speed but the safety: it round-trips, it is sealed under its own
 * purpose, and signing out takes it with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SipCacheTest {

    /** Stands in for the Keystore, with the purpose authenticated the way AES-GCM does it. */
    private class FakeBox : SecretBox {
        override fun seal(purpose: String, plain: String): String = "$purpose|$plain"

        override fun open(purpose: String, sealed: String): Unsealed =
            if (sealed.substringBefore('|') == purpose) {
                Unsealed.Ok(sealed.substringAfter('|'))
            } else {
                Unsealed.Unreadable
            }
    }

    private fun settings() = PathfinderSettings(RuntimeEnvironment.getApplication(), FakeBox())

    @Test
    fun the_saved_lines_come_back_unchanged() {
        val settings = settings()
        val payload = """[{"account":"100000_XYZ","password":"a secret"}]"""
        settings.saveSipCache(payload)
        assertEquals(payload, settings.sipCache())
    }

    @Test
    fun the_cache_is_not_readable_as_the_api_credentials() {
        val settings = settings()
        settings.saveSipCache("lines")
        settings.saveApi("someone@example.com", "an api password")
        // Separate purposes: the line cache must not be openable as the API blob, and the
        // API read must not return the line cache.
        assertEquals("lines", settings.sipCache())
        assertEquals("someone@example.com" to "an api password", settings.credentials())
    }

    @Test
    fun signing_out_takes_the_cache_with_it() {
        val settings = settings()
        settings.saveSipCache("lines")
        settings.clearApi()
        assertNull(settings.sipCache())
    }
}
