package com.willingtocode.voicepathfinder

import java.security.SecureRandom

/**
 * Turning an API sub-account into something a SIP stack can register, and the rules
 * around the one call that writes to the account.
 *
 * `setSubAccount` is the dangerous half of this file. Its required fields are marked
 * required for a reason: sending a subset is how an update clears a setting it did not
 * name, and a cleared `allowed_codecs` or `nat` is a sub-account that stops working for
 * every device on the account, not just this probe. So the rule here is that an update is
 * only built from a record that actually carried every required value, and it is refused
 * loudly otherwise. A failed update is recoverable in the portal; a silent reset is not
 * something the user can see from here.
 */
internal object Provisioning {

    const val G722 = "g722"
    const val ULAW = "ulaw"

    const val DEFAULT_EXPIRY = 300
    const val MIN_EXPIRY = 60
    const val MAX_EXPIRY = 3600

    const val PASSWORD_LENGTH = 24

    /** Lookalike characters are left out: a human may have to retype this in the portal. */
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val ALPHABET = LOWER + UPPER + DIGITS

    /** True when the API gave us nothing to register with, so a password must be set. */
    fun needsPassword(sub: SubAccount): Boolean = sub.password.isNullOrBlank()

    /**
     * A password of the shape the portal accepts. Generated rather than derived from
     * anything: a password derived from the account name is a password that can be
     * guessed from the account name.
     */
    fun generatePassword(random: SecureRandom = SecureRandom()): String {
        val out = StringBuilder(PASSWORD_LENGTH)
        out.append(LOWER[random.nextInt(LOWER.length)])
        out.append(UPPER[random.nextInt(UPPER.length)])
        out.append(DIGITS[random.nextInt(DIGITS.length)])
        while (out.length < PASSWORD_LENGTH) {
            out.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        // Fisher-Yates, so the three forced characters are not always in the same places.
        for (i in out.length - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val swap = out[i]
            out[i] = out[j]
            out[j] = swap
        }
        return out.toString()
    }

    /**
     * The documented `setSubAccount` parameters, built from what the API reported.
     *
     * Every field marked required in the reference is present or this throws. The
     * optional ones are passed only when the record carried a value, so the call does
     * not invent a setting.
     */
    fun updateParams(sub: SubAccount, password: String): List<Pair<String, String>> {
        val required = listOf(
            "auth_type" to sub.authType,
            "device_type" to sub.deviceType,
            "lock_international" to sub.lockInternational,
            "international_route" to sub.internationalRoute,
            // Documented as optional and required in practice: without it the provider
            // answers invalid_canada_routing and throws the entire request away.
            "canada_routing" to sub.canadaRouting,
            "music_on_hold" to sub.musicOnHold,
            "allowed_codecs" to sub.allowedCodecs,
            "dtmf_mode" to sub.dtmfMode,
            "nat" to sub.nat,
        )
        val missing = required.filter { it.second.isBlank() }.map { it.first }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "the API did not report ${missing.joinToString(", ")}, so writing this sub-account " +
                    "could reset it. Change this one in the portal instead.",
            )
        }

        val params = mutableListOf<Pair<String, String>>(
            "id" to sub.id,
            "auth_type" to sub.authType,
            "password" to password,
            "device_type" to sub.deviceType,
            "lock_international" to sub.lockInternational,
            "international_route" to sub.internationalRoute,
            "canada_routing" to sub.canadaRouting,
            "music_on_hold" to sub.musicOnHold,
            "allowed_codecs" to sub.allowedCodecs,
            "dtmf_mode" to sub.dtmfMode,
            "nat" to sub.nat,
            "sip_traffic" to if (sub.sipTraffic) "1" else "0",
            // Never the raw value: the API accepts 60 to 3600 and a missing field parses
            // as a default, so sending a zero here would be rejected outright.
            "max_expiry" to expires(sub).toString(),
        )
        if (sub.description.isNotBlank()) params += "description" to sub.description
        if (sub.callerId.isNotBlank()) params += "callerid_number" to sub.callerId
        if (sub.rtpTimeout.isNotBlank()) params += "rtp_timeout" to sub.rtpTimeout
        if (sub.rtpHoldTimeout.isNotBlank()) params += "rtp_hold_timeout" to sub.rtpHoldTimeout
        return params
    }

    /** `allowed_codecs` with [codec] added once, order preserved, nothing else touched. */
    fun withCodec(codecs: String, codec: String): String {
        val existing = codecs.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (existing.any { it.equals(codec, ignoreCase = true) }) return codecs
        return (existing + codec).joinToString(";")
    }

    /**
     * `allowed_codecs` with [codec] first, everything else after it in the order it was.
     *
     * The provider builds its SDP answer from this list, not from the order in our offer,
     * so this is the only lever that changes which codec it picks. It changes what every
     * device on the sub-account may negotiate, which is why it is a button.
     */
    fun withCodecFirst(codecs: String, codec: String): String {
        val rest = codecs.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            .filterNot { it.equals(codec, ignoreCase = true) }
        return (listOf(codec) + rest).joinToString(";")
    }

    /**
     * Which server to register against.
     *
     * The provider's own registration status is the best evidence, because it names the
     * POP another device is already using. Failing that, the POP the provider marks
     * recommended, then the first in its list. Null means the screen has to ask.
     */
    fun chooseServer(status: RegistrationStatus, servers: List<ServerPop>): String? {
        status.serverHostname?.takeIf { it.isNotBlank() }?.let { return it }
        servers.firstOrNull { it.recommended }?.let { return it.hostname }
        return servers.firstOrNull()?.hostname
    }

    /** What the sub-account's dialing mode means for the number we send. */
    fun dialingModeLabel(value: String): String = when (value.trim()) {
        "1" -> "E164, expects a leading +"
        "2" -> "NANPA, 10 or 11 digits"
        "" -> "account default"
        "0" -> "account default"
        else -> "code $value"
    }

    /** The sub-account's own ceiling, which is also what makes the expiry meaningful. */
    fun expires(sub: SubAccount): Int = sub.maxExpiry.coerceIn(MIN_EXPIRY, MAX_EXPIRY)

    /**
     * The credentials for the SIP stack, with the SIP username taken from `account`.
     * Nothing here is stored; see [SipCredentials].
     */
    fun credentials(
        sub: SubAccount,
        password: String,
        host: String,
        port: Int,
        tls: Boolean,
    ): SipCredentials =
        SipCredentials(
            host = Provider.host(host),
            port = port,
            username = sub.account,
            password = password,
            expiresSeconds = expires(sub),
            label = sub.label,
            codecs = sub.allowedCodecs,
            tls = tls,
        )
}
