package com.willingtocode.voicepathfinder

import android.app.Application
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What survives a restart.
 *
 * The regression this covers: `refreshAccount` set the list but never wrote the cache, so
 * every launch found nothing saved and read the account again. That put two slow calls in
 * front of a screen that already knew what to show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PathfinderCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var app: Application
    private lateinit var settings: PathfinderSettings

    /**
     * A pass-through box.
     *
     * The real one needs the Android Keystore, which Robolectric does not provide, and this
     * test is about the number cache rather than about the credentials.
     */
    private class PlainBox : SecretBox {
        override fun seal(purpose: String, plain: String) = "$purpose|$plain"

        override fun open(purpose: String, sealed: String): Unsealed =
            if (sealed.startsWith("$purpose|")) {
                Unsealed.Ok(sealed.removePrefix("$purpose|"))
            } else {
                Unsealed.Unreadable
            }
    }

    @Before
    fun start() {
        app = RuntimeEnvironment.getApplication()
        server = MockWebServer()
        server.start()
        settings = PathfinderSettings(app, PlainBox())
        settings.saveApi("user@example.com", "secret")
        settings.setEndpoint(server.url("/rest.php").toString())
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun reply(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    private fun awaitDids(pathfinder: Pathfinder): List<Did> {
        repeat(100) {
            if (pathfinder.dids.value.isNotEmpty()) return pathfinder.dids.value
            Thread.sleep(100)
        }
        return pathfinder.dids.value
    }

    private val accountsBody =
        """{"status":"success","accounts":[{"id":"99785","account":"100000_VoIP","username":"VoIP","password":"voip@house"}]}"""

    private val didsBody =
        """{"status":"success","dids":[{"did":"5551234567","description":"Desk","routing":"account:100000_VoIP","voicemail":"","dialtime":"60"}]}"""

    @Test
    fun `a read writes the list to disk and the next launch starts from it`() {
        reply(accountsBody)
        reply(didsBody)

        val first = Pathfinder(app, settings)
        first.refreshAccount()
        assertEquals(1, awaitDids(first).size)

        assertFalse("the read did not reach the disk", settings.didCache.isBlank())
        assertTrue("the freshness stamp was not written", first.checkedAt.value > 0L)
        assertEquals(first.checkedAt.value, settings.checkedAt)

        // A new instance is what a restart gives you.
        val reads = server.requestCount
        val second = Pathfinder(app, settings)
        assertEquals("the saved list should fill the screen", 1, second.dids.value.size)
        assertEquals("5551234567", second.dids.value.first().number)
        assertEquals("account:100000_VoIP", second.dids.value.first().routing)

        // Nothing is chosen here, so starting up has nothing to restore and nothing to read.
        second.startUp()
        Thread.sleep(500)
        assertEquals("launching made a network read", reads, server.requestCount)
    }

    @Test
    fun `a saved list is what the launch path uses instead of reading again`() {
        reply(accountsBody)
        reply(didsBody)

        val first = Pathfinder(app, settings)
        first.refreshAccount()
        assertEquals(1, awaitDids(first).size)

        val reads = server.requestCount
        val restarted = Pathfinder(app, settings)
        restarted.startUp()
        Thread.sleep(500)
        assertTrue("the list was read again at launch", server.requestCount == reads)
    }
}
