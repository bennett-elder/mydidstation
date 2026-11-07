package com.willingtocode.mydidstation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ApiException(val status: String) : Exception("VoIP.ms returned status=$status")

/** One number the account reports, and whether the account can message on it. */
data class DiscoveredDid(val did: String, val smsEnabled: Boolean)

data class RemoteMsg(
    val remoteId: Long,
    val contact: String,
    val incoming: Boolean,
    val body: String,
    val media: List<String>,
    val sentAt: Long,
    val carrierStatus: String,
)

/**
 * The one REST endpoint we talk to. Per-DID calls only: the API has no batch
 * method and no cursor, so the sync layer owns the windowing (D8).
 */
class VoipMs(private val settings: ApiSettings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = API_TZ
    }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = API_TZ
    }

    private suspend fun call(vararg params: Pair<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val creds = settings.credentials() ?: throw IllegalStateException("Not configured")
            val form = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                addFormDataPart("api_username", creds.first)
                addFormDataPart("api_password", creds.second)
                params.forEach { (k, v) -> addFormDataPart(k, v) }
            }.build()
            val request = Request.Builder().url(settings.endpoint()).post(form).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                JSONObject(response.body.string())
            }
        }

    private fun requireSuccess(json: JSONObject) {
        val status = json.optString("status")
        if (status != "success") throw ApiException(status)
    }

    suspend fun discoverDids(): List<DiscoveredDid> {
        val json = call("method" to "getDIDsInfo")
        requireSuccess(json)
        val array = json.optJSONArray("dids") ?: return emptyList()
        val out = mutableListOf<DiscoveredDid>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val did = normalizePhone(row.optString("did"))
            if (did.isEmpty()) continue
            // Absent means the API did not say, which is treated as enabled so a
            // shape change cannot silently hide every number.
            val enabled = row.optString("sms_enabled", "1").trim().lowercase()
            out.add(DiscoveredDid(did, enabled != "0" && enabled != "false"))
        }
        return out
    }

    /** One window, one DID: getMMS with all_messages=1 returns SMS and MMS together. */
    suspend fun fetchMessages(did: String, fromSec: Long, toSec: Long, limit: Int): List<RemoteMsg> {
        val json = call(
            "method" to "getMMS",
            "did" to did,
            "from" to dateFormat.format(Date(fromSec * 1000)),
            "to" to dateFormat.format(Date(toSec * 1000)),
            "limit" to limit.toString(),
            "all_messages" to "1",
            "timezone" to "-5",
        )
        val status = json.optString("status")
        if (status == "no_sms") return emptyList()
        if (status == "invalid_did") return emptyList()
        requireSuccess(json)

        val array = json.optJSONArray("sms") ?: return emptyList()
        val out = mutableListOf<RemoteMsg>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val remoteId = row.optString("id").toLongOrNull() ?: continue
            val sentAt = runCatching {
                stampFormat.parse(row.optString("date"))!!.time / 1000
            }.getOrNull() ?: continue
            val media = listOf(
                row.optString("col_media1"),
                row.optString("col_media2"),
                row.optString("col_media3"),
            ).filter { it.isNotEmpty() }
            out.add(
                RemoteMsg(
                    remoteId = remoteId,
                    contact = normalizePhone(row.optString("contact")),
                    incoming = row.optString("type") == "1",
                    body = row.optString("message"),
                    media = media,
                    sentAt = sentAt,
                    carrierStatus = row.optString("carrier_status"),
                )
            )
        }
        return out
    }

    /**
     * from and to are dates on the wire, not timestamps, so two instants on the
     * same day describe one indivisible window. The sync layer needs to know
     * when narrowing further is pointless.
     */
    fun sameApiDay(a: Long, b: Long): Boolean =
        dateFormat.format(Date(a * 1000)) == dateFormat.format(Date(b * 1000))

    suspend fun sendSms(did: String, destination: String, body: String): Long? {
        val json = call(
            "method" to "sendSMS",
            "did" to did,
            "dst" to destination,
            "message" to body,
        )
        val status = json.optString("status")
        if (status != "success") throw ApiException(status)
        return json.optString("sms").toLongOrNull()
    }

    companion object {
        /**
         * The API is asked for `timezone=-5`, a fixed offset, so every response is
         * a wall time at UTC-5 all year. Parsing those as `America/New_York` was an
         * hour wrong from March to November, because that zone runs at UTC-4 under
         * daylight saving, which quietly moved an hour of every summer timestamp.
         */
        private val API_TZ: TimeZone = TimeZone.getTimeZone("GMT-5")

        fun normalizePhone(raw: String): String {
            val digits = raw.filter { it.isDigit() }
            return if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
        }
    }
}
