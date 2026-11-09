package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules around writing to a sub-account.
 *
 * `setSubAccount` marks most of its parameters required. Sending a subset is how an
 * update clears a setting it did not name, and the damage is not visible from this app:
 * every device on the sub-account stops working. So the refusal cases below matter as
 * much as the happy path.
 */
class ProvisioningTest {

    private fun subAccount(
        password: String? = "voip@house",
        allowedCodecs: String = "ulaw;gsm",
        maxExpiry: Int = 3600,
        nat: String = "yes",
    ) = SubAccount(
        id = "99785",
        account = "100000_VoIP",
        description = "My VoIP Account",
        password = password,
        authType = "1",
        deviceType = "2",
        callerId = "5551234567",
        lockInternational = "0",
        canadaRouting = "2",
        internationalRoute = "1",
        musicOnHold = "jazz",
        allowedCodecs = allowedCodecs,
        dtmfMode = "auto",
        nat = nat,
        rtpTimeout = "60",
        rtpHoldTimeout = "600",
        sipTraffic = false,
        maxExpiry = maxExpiry,
        protocol = "1",
        dialingMode = "2",
    )

    @Test
    fun `a returned password needs no reset and a missing one does`() {
        assertFalse(Provisioning.needsPassword(subAccount()))
        assertTrue(Provisioning.needsPassword(subAccount(password = null)))
        assertTrue(Provisioning.needsPassword(subAccount(password = "  ")))
    }

    @Test
    fun `a generated password has every character class and no lookalikes`() {
        val password = Provisioning.generatePassword()
        assertEquals(Provisioning.PASSWORD_LENGTH, password.length)
        assertTrue(password.any { it.isLowerCase() })
        assertTrue(password.any { it.isUpperCase() })
        assertTrue(password.any { it.isDigit() })
        assertTrue(password.all { it.isLetterOrDigit() })
        for (lookalike in listOf('l', 'I', 'O', '0', '1')) {
            assertFalse("contains $lookalike", password.contains(lookalike))
        }
        assertNotEquals(password, Provisioning.generatePassword())
    }

    @Test
    fun `an update carries every required field and the new password`() {
        val params = Provisioning.updateParams(subAccount(), "newSecret123")
        val map = params.toMap()
        assertEquals("99785", map["id"])
        assertEquals("newSecret123", map["password"])
        assertEquals("1", map["auth_type"])
        assertEquals("2", map["device_type"])
        assertEquals("0", map["lock_international"])
        // The field the API documents as optional and refuses to work without.
        assertEquals("2", map["canada_routing"])
        assertEquals("1", map["international_route"])
        assertEquals("jazz", map["music_on_hold"])
        assertEquals("ulaw;gsm", map["allowed_codecs"])
        assertEquals("auto", map["dtmf_mode"])
        assertEquals("yes", map["nat"])
        assertEquals("0", map["sip_traffic"])
        assertEquals("3600", map["max_expiry"])
        assertEquals("60", map["rtp_timeout"])
        assertEquals("600", map["rtp_hold_timeout"])
    }

    @Test
    fun `an update is refused when a required field is missing`() {
        val fault = runCatching { Provisioning.updateParams(subAccount(nat = ""), "x") }.exceptionOrNull()
        assertTrue(fault is IllegalStateException)
        assertTrue(fault?.message.orEmpty().contains("nat"))
    }

    @Test
    fun `a codec is added once and the rest is left alone`() {
        assertEquals("ulaw;gsm;g722", Provisioning.withCodec("ulaw;gsm", "g722"))
        // Already present: the string is returned untouched, spacing included, because
        // rewriting it would be a change nobody asked for.
        assertEquals("ulaw;gsm;g722", Provisioning.withCodec("ulaw;gsm;g722", "g722"))
        assertEquals("ulaw; gsm ;g722", Provisioning.withCodec("ulaw; gsm ;g722", "g722"))
        assertEquals("g722", Provisioning.withCodec("", "g722"))
    }

    @Test
    fun `the server is chosen from the best evidence available`() {
        val servers = listOf(
            ServerPop("Atlanta, GA", "atlanta.voip.ms", "15", recommended = false),
            ServerPop("Houston, TX", "houston.voip.ms", "1", recommended = true),
        )
        assertEquals(
            "atlanta.voip.ms",
            Provisioning.chooseServer(RegistrationStatus(true, "atlanta.voip.ms", "UDP", "1.2.3.4", "5000"), servers),
        )
        assertEquals(
            "houston.voip.ms",
            Provisioning.chooseServer(RegistrationStatus(false, null, null, null, null), servers),
        )
        assertEquals(
            "atlanta.voip.ms",
            Provisioning.chooseServer(RegistrationStatus(false, null, null, null, null), servers.take(1)),
        )
        assertNull(Provisioning.chooseServer(RegistrationStatus(false, null, null, null, null), emptyList()))
    }

    @Test
    fun `the expiry is the sub-account's own, clamped to what the api accepts`() {
        assertEquals(3600, Provisioning.expires(subAccount(maxExpiry = 3600)))
        assertEquals(300, Provisioning.expires(subAccount(maxExpiry = 300)))
        assertEquals(3600, Provisioning.expires(subAccount(maxExpiry = 99999)))
        assertEquals(60, Provisioning.expires(subAccount(maxExpiry = 1)))
    }

    @Test
    fun `credentials take the sip username from account, not the description`() {
        val credentials = Provisioning.credentials(
            sub = subAccount(),
            password = "secret",
            host = "sip:atlanta.voip.ms:5060",
            port = 5060,
            tls = false,
        )
        assertEquals("atlanta.voip.ms", credentials.host)
        assertEquals("100000_VoIP", credentials.username)
        assertEquals("secret", credentials.password)
        assertEquals(3600, credentials.expiresSeconds)
        assertEquals("ulaw;gsm", credentials.codecs)
        assertEquals("UDP", credentials.transport)
        assertEquals(null, credentials.problem())
    }

    @Test
    fun `a codec can be put first as well as appended`() {
        // Appending is what the "allow G.722" button does, and it leaves the provider's
        // preference unchanged. Putting it first is the experiment.
        assertEquals("ulaw;g729;g722", Provisioning.withCodec("ulaw;g729", "g722"))
        assertEquals("g722;ulaw;g729", Provisioning.withCodecFirst("ulaw;g729;g722", "g722"))
        assertEquals("g722;ulaw;g729", Provisioning.withCodecFirst("ulaw;g729", "g722"))
        assertEquals("g722;ulaw", Provisioning.withCodecFirst("g722;ulaw", "g722"))
        // Case is not a difference, and no codec is ever listed twice.
        assertEquals("g722;ulaw", Provisioning.withCodecFirst("ulaw;G722", "g722"))
    }

    @Test
    fun `the dialing mode is described in words the screen can show`() {
        assertEquals("E164, expects a leading +", Provisioning.dialingModeLabel("1"))
        assertEquals("NANPA, 10 or 11 digits", Provisioning.dialingModeLabel("2"))
        assertEquals("account default", Provisioning.dialingModeLabel("0"))
        assertEquals("account default", Provisioning.dialingModeLabel(""))
        assertEquals("code 7", Provisioning.dialingModeLabel("7"))
    }

    @Test
    fun `tls is carried through to the credentials`() {
        val credentials = Provisioning.credentials(
            sub = subAccount(),
            password = "secret",
            host = "tampa1.voip.ms",
            port = 5061,
            tls = true,
        )
        assertEquals("TLS", credentials.transport)
        assertEquals(5061, credentials.port)
    }
}
