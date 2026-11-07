package com.willingtocode.mydidstation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ApiException(val status: String) : Exception("VoIP.ms returned status=$status")

/** One number the account reports, and whether the account can message on it. */
data class DiscoveredDid(val did: String, val smsEnabled: Boolean, val mailbox: String)

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
 * One mailbox, as getVoicemails reports it.
 *
 * There is deliberately no password field: the payload carries one, and this does not
 * read it, keep it or log it.
 */
data class MailboxInfo(
    val mailbox: String,
    val name: String,
    val newCount: Int,
    val urgent: Int,
    val transcription: Boolean,
)

/** One recording in one folder. The API's `date` carries no time of day. */
data class VoiceMailInfo(
    val mailbox: String,
    val folder: String,
    val messageNum: Int,
    val date: String,
    val callerId: String,
    val duration: String,
    val urgent: Boolean,
    val listened: Boolean,
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
            // The voicemail this number is routed to. Not unique and not required: several
            // numbers can share a mailbox, and a mailbox can have none.
            val mailbox = row.optString("voicemail").trim()
            out.add(DiscoveredDid(did, enabled != "0" && enabled != "false", mailbox))
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

    /**
     * Every mailbox on the account.
     *
     * The only call that enumerates mailboxes, and a mailbox exists independently of any
     * DID: a number's `voicemail` field points at one, several numbers can point at the
     * same one, and a box can have no number at all. So this is where the voicemail list
     * starts, never `getDIDsInfo`.
     */
    suspend fun mailboxes(): List<MailboxInfo> {
        val json = call("method" to "getVoicemails")
        if (json.optString("status") != "success") {
            if (isEmptyStatus(json)) return emptyList()
            throw ApiException(json.optString("status"))
        }
        val array = json.optJSONArray("voicemails") ?: return emptyList()
        val out = mutableListOf<MailboxInfo>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val mailbox = row.optString("mailbox")
            if (mailbox.isEmpty()) continue
            out.add(
                MailboxInfo(
                    mailbox = mailbox,
                    name = row.optString("name"),
                    newCount = row.optString("new").toIntOrNull() ?: 0,
                    urgent = row.optString("urgent").toIntOrNull() ?: 0,
                    transcription = row.optString("transcription").equals("yes", true),
                )
            )
        }
        return out
    }

    /** The recordings in one folder, or in every folder when none is named. */
    suspend fun voicemails(mailbox: String, folder: String? = null): List<VoiceMailInfo> {
        val params = mutableListOf("method" to "getVoicemailMessages", "mailbox" to mailbox)
        if (folder != null) params += "folder" to folder
        val json = call(*params.toTypedArray())
        if (json.optString("status") != "success") {
            if (isEmptyStatus(json)) return emptyList()
            throw ApiException(json.optString("status"))
        }
        val array = json.optJSONArray("messages") ?: return emptyList()
        val out = mutableListOf<VoiceMailInfo>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val number = row.optString("message_num").toIntOrNull() ?: continue
            out.add(
                VoiceMailInfo(
                    mailbox = row.optString("mailbox", mailbox),
                    folder = row.optString("folder"),
                    messageNum = number,
                    date = row.optString("date"),
                    callerId = normalizePhone(row.optString("callerid")),
                    duration = row.optString("duration"),
                    urgent = row.optString("urgent").equals("yes", true),
                    listened = row.optString("listened").equals("yes", true),
                )
            )
        }
        return out
    }

    /**
     * One recording, decoded from the base64 the API wraps it in.
     *
     * mp3, because that is the API's own default. The bytes are attacker-controlled, so
     * the encoded length is checked before anything is allocated for the decode, and the
     * decoded length is checked after, rather than trusting either the response or the
     * format that was asked for.
     */
    suspend fun voicemailAudio(mailbox: String, folder: String, messageNum: Int): ByteArray {
        val json = call(
            "method" to "getVoicemailMessageFile",
            "mailbox" to mailbox,
            "folder" to folder,
            "message_num" to messageNum.toString(),
        )
        requireSuccess(json)
        val row = json.optJSONArray("message")?.optJSONObject(0)
            ?: throw ApiException("no_message")
        val data = row.optString("data")
        if (!audioLengthAllowed(data.length)) throw ApiException("audio_too_large")
        val bytes = Base64.decode(data, Base64.DEFAULT)
        if (bytes.size > MAX_AUDIO_BYTES) throw ApiException("audio_too_large")
        return bytes
    }

    /**
     * Marks a recording listened or unlistened.
     *
     * The first server mutation the app makes outside sending a text, and the API moves
     * the message between INBOX and Old as a side effect, so a caller cannot keep using
     * (folder, message number) as an identity afterwards.
     */
    suspend fun markVoicemailListened(
        mailbox: String,
        folder: String,
        messageNum: Int,
        listened: Boolean,
    ) {
        val json = call(
            "method" to "markListenedVoicemailMessage",
            "mailbox" to mailbox,
            "folder" to folder,
            "message_num" to messageNum.toString(),
            "listened" to if (listened) "yes" else "no",
        )
        requireSuccess(json)
    }

    /** A `no_...` status means there is simply nothing there, which is not a failure. */
    private fun isEmptyStatus(json: JSONObject): Boolean =
        json.optString("status").startsWith("no_")

    /** Whether a base64 payload is small enough to decode at all. */
    internal fun audioLengthAllowed(encodedChars: Int): Boolean =
        encodedChars <= MAX_AUDIO_BASE64_CHARS

    companion object {
        /**
         * The API is asked for `timezone=-5`, a fixed offset, so every response is
         * a wall time at UTC-5 all year. Parsing those as `America/New_York` was an
         * hour wrong from March to November, because that zone runs at UTC-4 under
         * daylight saving, which quietly moved an hour of every summer timestamp.
         */
        private val API_TZ: TimeZone = TimeZone.getTimeZone("GMT-5")

        /** One recording's ceiling. A ten minute mp3 at 24 kbps is about 1.8 MB. */
        private const val MAX_AUDIO_BYTES = 8 * 1024 * 1024

        /** Encoded is about a third larger, so the pre-decode check has to be wider. */
        private const val MAX_AUDIO_BASE64_CHARS = 12 * 1024 * 1024

        fun normalizePhone(raw: String): String {
            val digits = raw.filter { it.isDigit() }
            return if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
        }
    }
}
