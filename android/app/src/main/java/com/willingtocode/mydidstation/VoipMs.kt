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

    /**
     * The recordings in one folder, or in every folder when none is named.
     *
     * A date range is not optional in practice. Without one the API is asked for the
     * mailbox's entire history, which produces the same Cloudflare 522 that a 92-day text
     * window does, so callers pass a bounded range.
     */
    suspend fun voicemails(
        mailbox: String,
        folder: String? = null,
        fromSec: Long? = null,
        toSec: Long? = null,
    ): List<VoiceMailInfo> {
        val params = mutableListOf("method" to "getVoicemailMessages", "mailbox" to mailbox)
        if (folder != null) params += "folder" to folder
        if (fromSec != null) params += "date_from" to apiDate(fromSec)
        if (toSec != null) params += "date_to" to apiDate(toSec)
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
        val data = firstData(json, mailbox, folder, messageNum)
        if (!audioLengthAllowed(data.length)) throw ApiException("audio_too_large")
        val bytes = try {
            decodeAudio(data)
        } catch (e: ApiException) {
            // Payload first, because the five characters are the diagnosis. The echoed
            // identity only earns its place when it differs from what was asked for.
            val echo = echoIdentity(json)
            val expected = "mailbox=$mailbox folder=$folder message_num=$messageNum"
            val differs = echo.isNotEmpty() && echo != expected
            throw ApiException(
                "no audio for $mailbox/$folder/$messageNum: ${e.message}" +
                    if (differs) " (the API echoed $echo)" else "",
            )
        }
        if (bytes.size > MAX_AUDIO_BYTES) throw ApiException("audio_too_large")
        return bytes
    }

    /**
     * Decodes the audio, coping with the base64 variants a real account has sent.
     *
     * `Base64.DEFAULT` alone threw "bad base-64" on some recordings. The decoder is
     * lenient in one direction and strict in another: it silently skips characters outside
     * the alphabet, and it throws only when the length cannot be a whole number of bytes.
     * So the payload is stripped to the alphabet first, which keeps a stray character from
     * corrupting an otherwise good recording, and the failure reports the length and how
     * much was discarded rather than repeating "bad base-64".
     */
    internal fun decodeAudio(raw: String): ByteArray {
        val cleaned = raw.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) throw ApiException("the audio payload was empty")
        val standard = cleaned.replace('-', '+').replace('_', '/')
        val stray = standard.count { it !in BASE64_ALPHABET }
        val usable = standard.filter { it in BASE64_ALPHABET }
        val padded = usable + "=".repeat((4 - usable.length % 4) % 4)
        return try {
            Base64.decode(padded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            val strayNote = if (stray > 0) ", $stray not in the alphabet" else ""
            throw ApiException(
                "the payload is ${usable.length} characters, starting " +
                    "\"${preview(raw)}\"$strayNote",
            )
        }
    }

    /**
     * The start of a payload, safe to show.
     *
     * A five character `data` cannot be audio, and the characters themselves are the whole
     * diagnosis: `false` is a sentinel, noise is a broken body. Anything unprintable
     * becomes a question mark so a binary payload cannot garble the screen.
     */
    private fun preview(raw: String, limit: Int = 40): String {
        val head = raw.take(limit).map { if (it.isISOControl() || it.code > 126) '?' else it }
        return head.joinToString("") + if (raw.length > limit) "…" else ""
    }

    /** The identity the API recognised, for a failure that has to explain itself. */
    private fun echoIdentity(json: JSONObject): String {
        val row = json.optJSONObject("message") ?: json.optJSONArray("message")?.optJSONObject(0)
            ?: return ""
        return listOf("mailbox", "folder", "message_num")
            .mapNotNull { key ->
                row.optString(key).takeIf { it.isNotEmpty() }?.let { "$key=$it" }
            }
            .joinToString(" ")
    }

    /**
     * The base64 out of a `getVoicemailMessageFile` response.
     *
     * The vendor documents `message` as an array holding one object. The real API returns
     * the object itself, and parsing only the documented array is why every recording on a
     * real account failed with nothing to show for it. Both shapes are accepted, and the
     * failure names the request and the fields that did arrive.
     */
    private fun firstData(
        json: JSONObject,
        mailbox: String,
        folder: String,
        messageNum: Int,
    ): String {
        val inArray = json.optJSONArray("message")?.optJSONObject(0)
        val inObject = json.optJSONObject("message")
        val data = inArray?.optString("data")
            ?: inObject?.optString("data")
            ?: json.optString("data")
        if (data.isNullOrBlank()) {
            // The API answers success with an empty `data` when the mailbox, folder and
            // number do not describe a recording. Echoing back what it recognised is what
            // separates a wrong identity from an empty file, and it is the only thing the
            // phone can tell us when nobody is watching the wire.
            val echo = echoIdentity(json)
            val keys = json.keys().asSequence().joinToString(",")
            throw ApiException(
                "no audio for $mailbox/$folder/$messageNum (fields: $keys)" +
                    if (echo.isNotEmpty()) " (echoed: $echo)" else "",
            )
        }
        return data
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

    /**
     * The voicemail date parameters are dates, not timestamps, and they are read in the
     * same fixed offset everything else is (D-API timezone).
     */
    private fun apiDate(epochSeconds: Long): String =
        dateFormat.format(Date(epochSeconds * 1000))

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

        /** The standard alphabet plus padding. Anything else is what breaks a decode. */
        private const val BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="

        fun normalizePhone(raw: String): String {
            val digits = raw.filter { it.isDigit() }
            return if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
        }
    }
}
