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
 * The call list between launches.
 *
 * Same contract as the number list: the saved copy draws first and a refresh replaces it
 * behind the content. These pin that a read is written down, that a restart uses it without
 * a request, and that a failed refresh does not erase what was already shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CallCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var app: Application
    private lateinit var settings: PathfinderSettings

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

    private val cdr = """{"status":"success","cdr":[{"date":"2026-11-09 20:14:03","destination":"15551230000",""" +
        """"disposition":"ANSWERED","description":"","seconds":"21","call_logs":"leg A"}]}"""

    private fun awaitRecords(pathfinder: Pathfinder): List<CallRecord> {
        repeat(100) {
            if (pathfinder.calls.value.records.isNotEmpty()) return pathfinder.calls.value.records
            Thread.sleep(100)
        }
        return pathfinder.calls.value.records
    }

    @Test
    fun `a read writes the call list and the next launch starts from it`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(cdr))

        val first = Pathfinder(app, settings)
        first.loadCalls()
        val read = awaitRecords(first)
        assertEquals(1, read.size)
        assertEquals("15551230000", read.first().destination)
        assertFalse("the read did not reach the disk", settings.callsCache.isBlank())
        assertTrue("no freshness stamp", first.callsCheckedAt.value > 0L)

        val requests = server.requestCount
        val second = Pathfinder(app, settings)
        assertEquals(1, second.calls.value.records.size)
        assertEquals("ANSWERED", second.calls.value.records.first().disposition)
        assertEquals(requests, server.requestCount)
    }

    @Test
    fun `a failed refresh keeps the saved list on screen`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(cdr))
        val first = Pathfinder(app, settings)
        first.loadCalls()
        assertEquals(1, awaitRecords(first).size)

        val second = Pathfinder(app, settings)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"status":"fail"}"""))
        second.loadCalls()
        Thread.sleep(1500)

        assertEquals("the saved list was replaced by the failure", 1, second.calls.value.records.size)
        assertTrue("the failure was not reported", second.calls.value.error != null)
    }

    @Test
    fun `every record in the window is kept, not the first eight`() {
        val many = (0 until 12).joinToString(",") { i ->
            """{"date":"2026-11-09 1$i:00:00","destination":"1555123000$i",""" +
                """"disposition":"NO ANSWER","description":"Inbound - Local Caller",""" +
                """"seconds":"0","account":"100000_in","call_logs":""}"""
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"success","cdr":[$many]}"""))

        val pathfinder = Pathfinder(app, settings)
        pathfinder.loadCalls()
        val records = awaitRecords(pathfinder)
        // The cap was eight, and it hid a missed call the log said had been read.
        assertEquals(12, records.size)
    }
}