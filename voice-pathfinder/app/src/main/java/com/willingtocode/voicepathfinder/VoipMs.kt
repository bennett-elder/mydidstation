package com.willingtocode.voicepathfinder

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** A response whose `status` was a word instead of `success`. */
class ApiException(val status: String) : Exception("VoIP.ms returned status=$status")

/**
 * A sub-account, as `getSubAccounts` reports it.
 *
 * The provider returns the SIP password here in the clear. That is their API, not this
 * app's choice, and it is why [SipCredentials] is never written to disk: the value
 * arrives over TLS, is held in memory for the session, and is fetched again when it is
 * needed rather than stored.
 *
 * `account` is the SIP username (`100000_VoIP`), which is not the same as `id` or
 * `description`. Using the description as a username is the kind of mistake that
 * produces a 403 that looks like a wrong password.
 */
data class SubAccount(
    val id: String,
    val account: String,
    val description: String,
    val password: String?,
    val authType: String,
    val deviceType: String,
    val callerId: String,
    val lockInternational: String,
    val internationalRoute: String,
    /**
     * Route code for Canada, which `getSubAccounts` reports and `setSubAccount` requires.
     *
     * Omitting it makes the provider answer `invalid_canada_routing` and reject the whole
     * request, so every write this app attempted on 2026-11-09 was refused before this
     * field was carried through. The API documents it as optional, which is wrong.
     */
    val canadaRouting: String,
    val musicOnHold: String,
    val allowedCodecs: String,
    val dtmfMode: String,
    val nat: String,
    val rtpTimeout: String,
    val rtpHoldTimeout: String,
    val sipTraffic: Boolean,
    val maxExpiry: Int,
    val protocol: String,
    /** 0 is the account default, 1 E164, 2 NANPA. Decides whether a number needs a plus. */
    val dialingMode: String,
) {
    val label: String get() = description.ifBlank { account }

    val codecList: List<String>
        get() = allowedCodecs.split(';').map { it.trim() }.filter { it.isNotEmpty() }
}

/** One POP from `getServersInfo`. */
data class ServerPop(
    val name: String,
    val hostname: String,
    val pop: String,
    val recommended: Boolean,
)

/**
 * What the provider last saw, from `getRegistrationStatus`.
 *
 * It is a second opinion about our own registration, and the only way to learn the
 * server hostname the account is actually expected to use: a sub-account does not name
 * one, because it is global and any POP will do.
 */
data class RegistrationStatus(
    val registered: Boolean,
    val serverHostname: String?,
    val transport: String?,
    val registerIp: String?,
    val registerPort: String?,
)

/**
 * One call as the provider recorded it.
 *
 * [logs] is the valuable part: it is the provider's own account of where the call went
 * and why it stopped, which is the only way to tell a routing failure from a media one
 * after the fact.
 */
data class CallRecord(
    val date: String,
    val destination: String,
    val disposition: String,
    val description: String,
    val seconds: Int,
    val logs: String,
    /**
     * The sub-account this leg belongs to, as the provider reports it.
     *
     * This is what makes the history filterable per number: a DID routes to a sub-account,
     * and the CDR row names that sub-account. Parsing it is cheaper and truer than keeping
     * a second, local list of which line placed what.
     */
    val account: String = "",
    /** Who the provider says was on the far end, for a call we did not place. */
    val callerId: String = "",
)

/**
 * The four REST functions this phase needs, and no more.
 *
 * The vendor's API cannot place a call; it can only describe the account and change it.
 * Three of these describe: the sub-account list including its SIP credentials, the POP
 * list, and the provider's own view of the registration. `setSubAccount` is included
 * because it is the only way to write back a generated password or turn on G.722, and it
 * is used narrowly for those two things.
 */
/**
 * One phone number, as `getDIDsInfo` reports it.
 *
 * [routing] is what decides whether a call to this number can reach us at all: it reads
 * `account:<sub-account>`, or `vm:<mailbox>`, or `fwd:<number>`, or `sys:...`. Only the
 * first kind rings a sub-account that this app can register.
 */
data class Did(
    val number: String,
    val description: String,
    val routing: String,
    val voicemail: String,
    val dialtime: Int,
) {
    /** The sub-account this DID rings, or null when it routes somewhere else. */
    val routedAccount: String?
        get() = routing.substringAfter("account:", "").takeIf { routing.startsWith("account:") && it.isNotBlank() }
}

class VoipMs(private val settings: ApiSettings) {

    /**
     * Reads are capped generously on purpose.
     *
     * Measured on 2026-11-09: a bare request to the endpoint took 9.97 s from a working
     * network, and a `setSubAccount` took 54 s. At a 30 s ceiling, `getDIDsInfo` was being
     * killed mid-flight and reported as `timeout`, which looked like a dead button.
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Writes get their own client and their own ceiling.
     *
     * `setSubAccount` rewrites the sub-account and is the slowest call the API has. At the
     * read timeout it timed out twice in a row on 2026-11-09 and the change had to be made
     * in the portal instead. The product app carries the same split for uploads for the
     * same reason.
     */
    private val writes = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(WRITE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun subAccounts(): List<SubAccount> {
        val json = call("getSubAccounts")
        val rows = json.optJSONArray("accounts") ?: return emptyList()
        val out = mutableListOf<SubAccount>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id").trim()
            val account = row.optString("account").trim()
            if (id.isEmpty() || account.isEmpty()) continue
            out += SubAccount(
                id = id,
                account = account,
                description = row.optString("description").trim(),
                password = row.optString("password").takeIf { it.isNotBlank() },
                authType = row.optString("auth_type").trim(),
                deviceType = row.optString("device_type").trim(),
                callerId = row.optString("callerid_number").trim(),
                lockInternational = row.optString("lock_international").trim(),
                internationalRoute = row.optString("international_route").trim(),
                canadaRouting = row.optString("canada_routing").trim()
                    .ifBlank { row.optString("canada_route").trim() },
                musicOnHold = row.optString("music_on_hold").trim(),
                allowedCodecs = row.optString("allowed_codecs").trim(),
                dtmfMode = row.optString("dtmf_mode").trim(),
                nat = row.optString("nat").trim(),
                rtpTimeout = row.optString("rtp_timeout").trim(),
                rtpHoldTimeout = row.optString("rtp_hold_timeout").trim(),
                sipTraffic = parseFlag(row.optString("sip_traffic")),
                maxExpiry = row.optString("max_expiry").trim().toIntOrNull() ?: 300,
                protocol = row.optString("protocol").trim(),
                dialingMode = row.optString("dialing_mode").trim(),
            )
        }
        return out
    }

    suspend fun dids(): List<Did> {
        val json = call("getDIDsInfo")
        val rows = json.optJSONArray("dids") ?: return emptyList()
        val out = mutableListOf<Did>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val number = row.optString("did").trim()
            if (number.isEmpty()) continue
            out += Did(
                number = number,
                description = row.optString("description").trim(),
                routing = row.optString("routing").trim(),
                voicemail = row.optString("voicemail").trim(),
                dialtime = row.optString("dialtime").trim().toIntOrNull() ?: 0,
            )
        }
        return out
    }

    suspend fun servers(): List<ServerPop> {
        val json = call("getServersInfo")
        val rows = json.optJSONArray("servers") ?: return emptyList()
        val out = mutableListOf<ServerPop>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val hostname = row.optString("server_hostname").trim()
            if (hostname.isEmpty()) continue
            out += ServerPop(
                name = row.optString("server_name").trim(),
                hostname = hostname,
                pop = row.optString("server_pop").trim(),
                recommended = row.optString("server_recommended").trim().equals("yes", true),
            )
        }
        return out
    }

    suspend fun registrationStatus(account: String): RegistrationStatus {
        val json = call("getRegistrationStatus", "account" to account)
        val rows = json.optJSONArray("registrations")
        val first = if (rows != null && rows.length() > 0) rows.optJSONObject(0) else null
        return RegistrationStatus(
            registered = json.optString("registered").equals("yes", true),
            serverHostname = first?.optString("server_hostname")?.trim()?.takeIf { it.isNotEmpty() },
            transport = first?.optString("register_transport")?.trim()?.takeIf { it.isNotEmpty() },
            registerIp = first?.optString("register_ip")?.trim()?.takeIf { it.isNotEmpty() },
            registerPort = first?.optString("register_port")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Writes the documented fields back. Callers build the list in [Provisioning]. */
    suspend fun setSubAccount(params: List<Pair<String, String>>): Boolean {
        val json = callWith(writes, "setSubAccount", *params.toTypedArray())
        return json.optString("status") == "success"
    }

    /**
     * Today's calls, including failed ones, from `getCDR`.
     *
     * Asked with every disposition enabled because the calls this app makes are usually
     * failures at this stage, and a record that only lists answered calls would be empty
     * exactly when it is needed.
     */
    suspend fun callRecords(fromDate: String, toDate: String): List<CallRecord> {
        val json = call(
            "getCDR",
            "date_from" to fromDate,
            "date_to" to toDate,
            "answered" to "1",
            "noanswer" to "1",
            "busy" to "1",
            "failed" to "1",
            "timezone" to API_TIMEZONE,
        )
        val rows = json.optJSONArray("cdr") ?: return emptyList()
        val out = mutableListOf<CallRecord>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            out += CallRecord(
                date = row.optString("date").trim(),
                destination = row.optString("destination").trim(),
                disposition = row.optString("disposition").trim(),
                description = row.optString("description").trim(),
                seconds = row.optString("seconds").trim().toIntOrNull() ?: 0,
                logs = row.optString("call_logs").trim(),
                account = row.optString("account").trim(),
                callerId = row.optString("callerid").trim(),
            )
        }
        return out
    }

    private suspend fun call(method: String, vararg params: Pair<String, String>): JSONObject =
        callWith(http, method, *params)

    private suspend fun callWith(
        client: OkHttpClient,
        method: String,
        vararg params: Pair<String, String>,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val credentials = settings.credentials() ?: throw IllegalStateException("Not configured")
            val form = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                addFormDataPart("api_username", credentials.first)
                addFormDataPart("api_password", credentials.second)
                addFormDataPart("method", method)
                params.forEach { (key, value) -> addFormDataPart(key, value) }
            }.build()
            val request = Request.Builder().url(settings.endpoint()).post(form).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $method")
                val json = JSONObject(response.body.string())
                val status = json.optString("status")
                if (status != "success") throw ApiException(status)
                json
            }
        }

    /** VoIP.ms reports booleans as 1/0, and sometimes as words. */
    private fun parseFlag(value: String): Boolean {
        val text = value.trim().lowercase()
        return text == "1" || text == "yes" || text == "true"
    }

    private companion object {
        const val HTTP_TIMEOUT_SECONDS = 90L
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 60L
        const val WRITE_TIMEOUT_SECONDS = 120L
        const val WRITE_CALL_TIMEOUT_SECONDS = 180L

        /** The API reports call times in this offset unless told otherwise. */
        const val API_TIMEZONE = "-5"
    }
}
