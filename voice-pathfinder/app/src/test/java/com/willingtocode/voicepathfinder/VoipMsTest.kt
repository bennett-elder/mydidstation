package com.willingtocode.voicepathfinder

import android.app.Application
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The REST layer against the vendor's documented response shapes.
 *
 * Robolectric is here for `org.json`, the implementation the app ships against, and the
 * bodies include fields this app ignores so a rename shows up as a failure rather than
 * as silence. The recorded shapes are from `notes/voipms-api.md`, not invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VoipMsTest {

    private lateinit var server: MockWebServer

    private class FakeSettings(private val url: String) : ApiSettings {
        override fun credentials() = "user@example.com" to "secret"
        override fun endpoint() = url
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun api() = VoipMs(FakeSettings(server.url("/rest.php").toString()))

    private fun reply(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    @Test
    fun `a sub-account carries the sip username and the password the api returns`() = runBlocking {
        reply(
            """{"status":"success","accounts":[{"id":"99785","account":"100000_VoIP","username":"VoIP",""" +
                """"description":"My VoIP Account","protocol":"1","auth_type":"1","password":"voip@house",""" +
                """"ip":"","device_type":"2","callerid_number":"5551234567","canada_routing":"2",""" +
                """"lock_international":"0","international_route":"1","music_on_hold":"jazz",""" +
                """"allowed_codecs":"ulaw;gsm","dtmf_mode":"auto","nat":"yes","sip_traffic":"0",""" +
                """"max_expiry":"3600","rtp_timeout":"60","rtp_hold_timeout":"600"}]}""",
        )
        val accounts = api().subAccounts()
        assertEquals(1, accounts.size)
        val sub = accounts.first()
        assertEquals("99785", sub.id)
        assertEquals("100000_VoIP", sub.account)
        assertEquals("My VoIP Account", sub.label)
        assertEquals("voip@house", sub.password)
        assertEquals(listOf("ulaw", "gsm"), sub.codecList)
        assertFalse(sub.sipTraffic)
        assertEquals(3600, sub.maxExpiry)
        assertEquals("60", sub.rtpTimeout)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("getSubAccounts"))
        assertTrue(body.contains("user@example.com"))
    }

    @Test
    fun `a sub-account without a password is reported as such`() = runBlocking {
        reply(
            """{"status":"success","accounts":[{"id":"1","account":"1_x","password":"","allowed_codecs":"ulaw"}]}""",
        )
        val sub = api().subAccounts().first()
        assertNull(sub.password)
        assertTrue(Provisioning.needsPassword(sub))
    }

    @Test
    fun `servers carry the recommended flag and the hostname`() = runBlocking {
        reply(
            """{"status":"success","servers":[{"server_name":"Houston, TX","server_hostname":"houston.voip.ms",""" +
                """"server_ip":"209.62.1.2","server_country":"USA","server_pop":"1","server_recommended":"Yes"}]}""",
        )
        val servers = api().servers()
        assertEquals(1, servers.size)
        assertEquals("houston.voip.ms", servers.first().hostname)
        assertTrue(servers.first().recommended)
    }

    @Test
    fun `a registration status names the server the account is using`() = runBlocking {
        reply(
            """{"status":"success","registered":"yes","rerouted":"1","from_server_pop":"65","registrations":[""" +
                """{"server_name":"Atlanta, GA","server_hostname":"atlanta.voip.ms","server_ip":"174.34.146.162",""" +
                """"server_pop":"15","register_ip":"189.148.32.179","register_port":"59870",""" +
                """"register_transport":"UDP"}]}""",
        )
        val status = api().registrationStatus("100000_VoIP")
        assertTrue(status.registered)
        assertEquals("atlanta.voip.ms", status.serverHostname)
        assertEquals("UDP", status.transport)
        assertEquals("59870", status.registerPort)
    }

    @Test
    fun `an unregistered account reports no server`() = runBlocking {
        reply("""{"status":"success","registered":"no","registrations":[]}""")
        val status = api().registrationStatus("100000_VoIP")
        assertFalse(status.registered)
        assertNull(status.serverHostname)
    }

    @Test
    fun `setSubAccount sends every parameter it was given`() = runBlocking {
        reply("""{"status":"success"}""")
        val params = listOf("id" to "1", "password" to "abc", "allowed_codecs" to "ulaw;g722")
        assertTrue(api().setSubAccount(params))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("setSubAccount"))
        assertTrue(body.contains("name=\"password\""))
        assertTrue(body.contains("abc"))
        assertTrue(body.contains("ulaw;g722"))
    }

    @Test
    fun `call records carry the disposition and the provider's own log`() = runBlocking {
        reply(
            """{"status":"success","cdr":[{"date":"2026-11-09 12:36:48",""" +
                """"callerid":"\"Me\" <5551112222>","destination":"5551234567",""" +
                """"description":"Outbound - Customer","account":"100000_VoIP",""" +
                """"disposition":"FAILED","duration":"00:00:00","seconds":"0",""" +
                """"rate":"0.0","total":"0.0","uniqueid":"1","destination_type":"IN:USA",""" +
                """"call_logs":"Routing to sub-account: 100000_VoIP\nHangup: Channel is unavailable"}]}""",
        )
        val records = api().callRecords("2026-11-09", "2026-11-09")
        assertEquals(1, records.size)
        assertEquals("FAILED", records.first().disposition)
        assertEquals(0, records.first().seconds)
        assertTrue(records.first().logs.contains("Channel is unavailable"))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("getCDR"))
        assertTrue(body.contains("failed"))
    }

    @Test
    fun `a refused call is an ApiException carrying the vendor's word`() {
        reply("""{"status":"invalid_username"}""")
        val fault = assertThrows(ApiException::class.java) {
            runBlocking { api().subAccounts() }
        }
        assertEquals("invalid_username", fault.status)
    }

    @Test
    fun `an http failure is reported with its code`() {
        server.enqueue(MockResponse().setResponseCode(503))
        val fault = assertThrows(IOException::class.java) {
            runBlocking { api().servers() }
        }
        assertTrue(fault.message.orEmpty().contains("503"))
    }
}
