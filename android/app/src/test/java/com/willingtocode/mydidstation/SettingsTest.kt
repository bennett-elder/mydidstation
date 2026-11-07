package com.willingtocode.mydidstation

import android.app.Application
import android.content.Context
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The credential contract, exercised without a Keystore.
 *
 * Robolectric has no `AndroidKeyStore` provider, so the real box cannot run here and
 * every test drives [Settings] through a fake. The real key path is checked on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsTest {

    private lateinit var context: Context

    /**
     * Stand-in for the Keystore. Not secret, only opaque to a grep, which is enough to
     * prove the plaintext never lands in the preference file.
     */
    private class FakeBox(private val prefix: String = "fake:") : SecretBox {

        /** The value is there and will not open. */
        var corrupt = false

        /** The Keystore is not answering, which says nothing about the value. */
        var unavailable = false

        var failToSeal = false

        override fun seal(purpose: String, plain: String): String {
            if (failToSeal) throw IllegalStateException("no keystore")
            return head(purpose) + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)
        }

        override fun open(purpose: String, sealed: String): Unsealed {
            if (unavailable) return Unsealed.Unavailable
            val head = head(purpose)
            if (corrupt || !sealed.startsWith(head)) return Unsealed.Unreadable
            val plain = runCatching {
                String(Base64.decode(sealed.removePrefix(head), Base64.NO_WRAP))
            }.getOrNull() ?: return Unsealed.Unreadable
            return Unsealed.Ok(plain)
        }

        private fun head(purpose: String) = prefix + purpose + ":"
    }

    @Before
    fun fresh() {
        context = RuntimeEnvironment.getApplication()
        raw().edit().clear().commit()
        context.deleteSharedPreferences(Settings.LEGACY_PREFS)
    }

    private fun raw() = context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)

    private fun settings(box: SecretBox = FakeBox()) = Settings(context, box)

    @Test
    fun `a saved username and password come back out`() {
        val s = settings()
        s.save("user@example.com", "hunter2")
        assertEquals("user@example.com" to "hunter2", s.credentials())
    }

    @Test
    fun `the username is trimmed and the password is not`() {
        val s = settings()
        s.save("  user@example.com\n", "  hunter2  ")
        assertEquals("user@example.com" to "  hunter2  ", s.credentials())
    }

    @Test
    fun `nothing saved means no credentials`() {
        assertNull(settings().credentials())
    }

    @Test
    fun `a blank username reads as unset`() {
        val s = settings()
        s.save("   ", "hunter2")
        assertNull(s.credentials())
    }

    @Test
    fun `an empty password reads as unset`() {
        val s = settings()
        s.save("user@example.com", "")
        assertNull(s.credentials())
    }

    @Test
    fun `a value that will not open reads as unset rather than crashing`() {
        val box = FakeBox()
        val s = settings(box)
        s.save("user@example.com", "hunter2")
        box.corrupt = true
        assertNull(s.credentials())
    }

    @Test
    fun `a value that will not open is dropped instead of being retried`() {
        val box = FakeBox()
        val s = settings(box)
        s.save("user@example.com", "hunter2")
        box.corrupt = true
        s.credentials()
        assertFalse(raw().contains(Settings.KEY_CREDENTIALS))
    }

    @Test
    fun `a value is kept while the keystore is unavailable`() {
        val box = FakeBox()
        val s = settings(box)
        s.save("user@example.com", "hunter2")
        box.unavailable = true
        assertNull(s.credentials())
        assertTrue(raw().contains(Settings.KEY_CREDENTIALS))
    }

    @Test
    fun `the credentials come back when the keystore does`() {
        val box = FakeBox()
        val s = settings(box)
        s.save("user@example.com", "hunter2")
        box.unavailable = true
        assertNull(s.credentials())
        box.unavailable = false
        assertEquals("user@example.com" to "hunter2", s.credentials())
    }

    @Test
    fun `a blob sealed for another purpose does not open`() {
        val box = FakeBox()
        val s = settings(box)
        raw().edit().putString(
            Settings.KEY_CREDENTIALS,
            box.seal("hijacked", """{"email":"user@example.com","password":"hunter2"}"""),
        ).commit()
        assertNull(s.credentials())
    }

    @Test
    fun `the plaintext never reaches the preference file`() {
        val s = settings()
        s.save("user@example.com", "hunter2")
        val stored = raw().all.values.joinToString(" ")
        assertFalse(stored.contains("hunter2"))
        assertFalse(stored.contains("user@example.com"))
    }

    @Test
    fun `a seal failure leaves the file untouched`() {
        val s = Settings(context, KeystoreBox())
        // The real box has no provider under Robolectric, which is the same failure a
        // device with an unusable Keystore produces.
        runCatching { s.save("user@example.com", "hunter2") }
        assertFalse(raw().contains(Settings.KEY_CREDENTIALS))
    }

    @Test
    fun `email reads back empty before anything is saved`() {
        assertEquals("", settings().email())
    }

    @Test
    fun `email reads back the saved username`() {
        val s = settings()
        s.save("user@example.com", "hunter2")
        assertEquals("user@example.com", s.email())
    }

    @Test
    fun `the old encrypted file is discarded`() {
        context.getSharedPreferences(Settings.LEGACY_PREFS, Context.MODE_PRIVATE)
            .edit().putString("api_password", "ciphertext").commit()
        // Constructing alone must not do file work, so the cleanup is its own call now.
        val settings = settings()
        assertFalse(
            "construction must not delete anything",
            context.getSharedPreferences(Settings.LEGACY_PREFS, Context.MODE_PRIVATE).all.isEmpty(),
        )
        settings.migrateLegacy()
        val left = context.getSharedPreferences(Settings.LEGACY_PREFS, Context.MODE_PRIVATE)
        assertTrue(left.all.isEmpty())
    }

    @Test
    fun `the legacy cleanup can run twice`() {
        settings().migrateLegacy()
        settings().migrateLegacy()
    }

    @Test
    fun `the endpoint falls back to the real API when unset`() {
        assertEquals(Settings.DEFAULT_ENDPOINT, settings().endpoint())
    }

    @Test
    fun `a custom endpoint round-trips`() {
        val s = settings()
        s.setEndpoint("http://10.0.2.2:8765/rest.php")
        assertEquals("http://10.0.2.2:8765/rest.php", s.endpoint())
        assertTrue(s.usingCustomEndpoint())
    }

    @Test
    fun `setting the default endpoint clears the override`() {
        val s = settings()
        s.setEndpoint("http://10.0.2.2:8765/rest.php")
        s.setEndpoint(Settings.DEFAULT_ENDPOINT)
        assertEquals(Settings.DEFAULT_ENDPOINT, s.endpoint())
        assertFalse(s.usingCustomEndpoint())
    }

    @Test
    fun `foreground polling defaults to sixty seconds`() {
        assertEquals(60, settings().foregroundSeconds())
    }

    @Test
    fun `background polling defaults to fifteen minutes and never goes below`() {
        val s = settings()
        assertEquals(15, s.backgroundMinutes())
        s.setBackgroundMinutes(5)
        assertEquals(15, s.backgroundMinutes())
    }

    @Test
    fun `notifications default to on and have not been asked for`() {
        val s = settings()
        assertTrue(s.notificationsEnabled())
        assertFalse(s.notifyAsked())
    }

    @Test
    fun `the last poll starts at zero`() {
        assertEquals(0L, settings().lastPollAt())
    }

    /**
     * The bug this replaced: `Container` built the old encrypted store in its
     * constructor, so a device with an unusable Keystore crashed on launch.
     */
    @Test
    fun `a container can be built and read with no keystore available`() {
        val container = Container(context)
        assertEquals("", container.settings.email())
        assertNull(container.settings.credentials())
    }
}
