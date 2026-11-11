package com.willingtocode.voicepathfinder

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the probe is doing, in the words the screen shows. */
sealed interface PathState {
    data object Unregistered : PathState
    data class Registering(val attempt: Int) : PathState
    data class Registered(val grantedSeconds: Int, val at: Long) : PathState
    data class RegistrationFailed(val reason: String) : PathState
    data class Dialing(val number: String) : PathState
    data class Ringing(val number: String) : PathState
    data class Connected(
        val number: String,
        val codec: String,
        val encrypted: Boolean,
        val audio: Boolean,
        val at: Long,
    ) : PathState
    data class Ended(val reason: String) : PathState
    data class Failed(val reason: String) : PathState
}

/** Counters for the call, refreshed four times a second rather than per packet. */
data class MediaStats(
    val framesSent: Long = 0,
    val framesReceived: Long = 0,
    val lost: Long = 0,
    val duplicates: Long = 0,
    val late: Long = 0,
    val reordered: Long = 0,
    val held: Int = 0,
    val sendLevel: Float = 0f,
    val receiveLevel: Float = 0f,
    val localRtpPort: Int = 0,
    val remoteMedia: String = "",
    val echoCancelled: Boolean = false,
    val speaker: Boolean = false,
)

/**
 * The answers to the questions this app exists to answer, filled in while it runs and
 * copied out as text. Keeping them apart from the log is deliberate: a log is evidence,
 * and this is the finding.
 */
data class ProbeAnswers(
    val offered: String = "",
    val protocol: String = "",
    val encrypted: Boolean = false,
    val cryptoLines: Int = 0,
    val negotiated: String = "",
    val payloadTypes: String = "",
    val ptime: Int? = null,
    val remoteMedia: String = "",
    val grantedSeconds: Int = 0,
    val refreshSeconds: Long = 0,
    val inviteMillis: Long = -1,
    val incomingInvites: Int = 0,
)

sealed interface AccountListState {
    data object Idle : AccountListState
    data object Loading : AccountListState
    data object Loaded : AccountListState
    data class Failed(val reason: String) : AccountListState
}

/** What the provider's call records say, once they have been asked for. */
data class CallsView(
    val loading: Boolean = false,
    val error: String? = null,
    val records: List<CallRecord> = emptyList(),
)

/**
 * Where the credentials came from, stated so the screen can be honest about it.
 *
 * [generatedPassword] means this app wrote to the sub-account, which is the one action
 * here with consequences outside the phone. It is a finding, not a detail.
 */
data class ProvisionDetail(
    val generatedPassword: Boolean,
    val serverSource: String,
    val registered: Boolean,
    val registeredOn: String,
    /** UDP, TCP or TLS, as the provider reports it for whatever device is registered. */
    val transport: String,
    val sipTraffic: Boolean,
    val codecs: String,
    val maxExpiry: Int,
)

sealed interface ProvisionState {
    data object Idle : ProvisionState
    data class Working(val step: String) : ProvisionState
    data class Failed(val reason: String) : ProvisionState
    data class Ready(val credentials: SipCredentials, val detail: ProvisionDetail) : ProvisionState
}

internal class SipFault(message: String) : Exception(message)

/**
 * Adds the dialog's local tag to a header that does not carry one yet.
 *
 * An inbound INVITE's To has no tag: the tag is ours to invent when we answer. A BYE that
 * goes out without it belongs to no dialog, and the provider answers by ignoring it, which is
 * how a hang-up left the caller holding a call that showed as ended here.
 */
internal fun withLocalTag(header: String, tag: String): String =
    if (header.contains("tag=", ignoreCase = true)) header else "$header;tag=$tag"

/**
 * The whole probe, in one object that outlives the screen.
 *
 * It holds the SIP registration and at most one call. A service would be the right shape
 * for a product and is explicitly out of scope for Phase 0, so the application object
 * owns this and the activity only observes it. Rotating the phone therefore does not
 * drop a call, which matters when the thing being tested is audio.
 */
class Pathfinder(
    context: Context,
    private val settings: PathfinderSettings = PathfinderSettings(context.applicationContext),
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = VoipMs(settings)
    private val random = SecureRandom()
    private val cseqLock = Any()

    private val _state = MutableStateFlow<PathState>(PathState.Unregistered)

    /**
     * Registration and readiness. Readiness reporting writes here, so this flow answers
     * "can this phone take calls" and never "is a call up".
     */
    val state: StateFlow<PathState> = _state

    private val _call = MutableStateFlow<PathState?>(null)

    /**
     * The live call, or null when there is none.
     *
     * Separate from [state] because the two were one flow and fought: readiness publishing
     * wrote `Unregistered` into the same place the call had written `Connected`, so a finished
     * call arrived as "nothing registered" and anything watching it had to guess which
     * lifecycle it was looking at. Two questions, two flows.
     */
    val callState: StateFlow<PathState?> = _call

    /** The live call's counters: the product's call if one is up, otherwise the probe's. */
    // An answered incoming call runs its own media session, and the screen reads this. Without
    // the middle case the call screen showed zeros during an inbound call, which read as "no
    // media" when the frames were actually leaving the phone.
    val stats: StateFlow<MediaStats>
        get() = outbound?.mediaStats ?: answered?.media?.stats ?: noCallStats

    private val _answers = MutableStateFlow(ProbeAnswers())
    val answers: StateFlow<ProbeAnswers> = _answers

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private val lines = ArrayDeque<String>()

    val apiSettings: PathfinderSettings get() = settings

    /**
     * The API-driven half: the sub-accounts the account owns, and the state of turning
     * one of them into SIP credentials.
     *
     * Kept as state rather than as functions returning values, because the screen has to
     * survive rotation while a request is in flight, and because the password this
     * produces must never end up in a `remember` that is restored from a bundle.
     */
    private val _accounts = MutableStateFlow<List<SubAccount>>(emptyList())
    val accounts: StateFlow<List<SubAccount>> = _accounts

    private val _accountState = MutableStateFlow<AccountListState>(AccountListState.Idle)
    val accountState: StateFlow<AccountListState> = _accountState

    private val _provision = MutableStateFlow<ProvisionState>(ProvisionState.Idle)
    val provision: StateFlow<ProvisionState> = _provision

    private val _calls = MutableStateFlow(CallsView())
    val calls: StateFlow<CallsView> = _calls

    private val _dids = MutableStateFlow<List<Did>>(emptyList())
    val dids: StateFlow<List<Did>> = _dids

    private val _checkedAt = MutableStateFlow(settings.checkedAt)
    val checkedAt: StateFlow<Long> = _checkedAt

    /** When the call list was last read, 0 for never. The same contract as [checkedAt]. */
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted

    private val _callsCheckedAt = MutableStateFlow(settings.callsCheckedAt)
    val callsCheckedAt: StateFlow<Long> = _callsCheckedAt

    /** The last account-read failure, so a screen can say why nothing appeared. */
    private val _fetchError = MutableStateFlow<String?>(null)
    val fetchError: StateFlow<String?> = _fetchError

    /** True while an account read is in flight, so the screen can say "loading" not "empty". */
    private val _fetching = MutableStateFlow(false)
    val fetching: StateFlow<Boolean> = _fetching

    /** One registration per sub-account carrying a chosen number. */
    private val _activeLines = MutableStateFlow<List<Line>>(emptyList())
    internal val activeLines: StateFlow<List<Line>> = _activeLines

    @Volatile private var prepared: SubAccount? = null

    /**
     * The password for [prepared], held in memory only.
     *
     * Not persisted and not logged. It exists so a second write to the same sub-account
     * (the G.722 button) does not have to re-read a secret just to send it back.
     */
    @Volatile private var preparedPassword: String? = null

    @Volatile private var credentials: SipCredentials? = null

    /**
     * Whether the registration is believed live, kept apart from [PathState].
     *
     * The state describes the call as well as the line, so it says `Ended` when a call
     * finishes. Refusing to dial on that basis would mean re-registering between every
     * two calls, which is not what happened on the wire.
     */
    @Volatile private var registered = false
    @Volatile private var sipLink: SipLink? = null
    @Volatile private var localIp: String = ""
    @Volatile private var localSipPort: Int = 0
    @Volatile private var callId: String = ""
    @Volatile private var fromTag: String = ""
    private var cseq = 0

    private val pending = ConcurrentHashMap<String, Channel<SipMessage>>()
    private var sipReceiver: Job? = null
    private var refresh: Job? = null
    private var keepalive: Job? = null

    @Volatile private var currentInvite: SipMessage? = null
    @Volatile private var dialog: Dialog? = null

    /** A confirmed dialog, holding what BYE and re-INVITE have to repeat. */
    private data class Dialog(
        val remoteTarget: String,
        val from: String,
        val to: String,
        val callId: String,
        var cseq: Int,
    )


    /** The call placed from the dial page, if any. One at a time for now. */
    @Volatile private var outbound: Call? = null

    /** Counters for the probe-less case: the app shows the call's own when one is up. */
    private val noCallStats = MutableStateFlow(MediaStats())

    /** The Expires this client asks for, when it differs from what the account allows. */
    @Volatile private var expiryRequest: Int? = null

    /** The inline key this call put in its offer, base64, needed to decrypt what comes back. */
    @Volatile private var localCryptoKey = ""


    // ---------------------------------------------------------------- public surface

    /** Registers, replacing any existing registration and ending any call. */
    fun register(credentials: SipCredentials, requestedExpiry: Int? = null) {
        val problem = credentials.problem()
        if (problem != null) {
            note(problem)
            _state.value = PathState.RegistrationFailed(problem)
            return
        }
        scope.launch {
            refresh?.cancel()
            refresh = null
            endCall("replaced by a new registration", failed = false)
            closeTransport()
            this@Pathfinder.credentials = credentials
            expiryRequest = requestedExpiry
            registered = false
            _answers.update { ProbeAnswers(offered = it.offered) }
            _state.value = PathState.Registering(1)
            try {
                openTransport(credentials)
                val granted = doRegister(credentials)
                registered = true
                _state.value = PathState.Registered(granted, System.currentTimeMillis())
                scheduleRefresh(credentials, granted)
            } catch (fault: Exception) {
                registered = false
                note("registration failed: ${describe(fault)}")
                _state.value = PathState.RegistrationFailed(describe(fault))
                closeTransport()
            }
        }
    }

    /** Best-effort deregistration, then the socket goes. */
    fun unregister() {
        scope.launch {
            refresh?.cancel()
            refresh = null
            val credentials = credentials
            if (credentials != null && sipLink != null) {
                runCatching { send(requestRegister(credentials, 0, null, nextCSeq())) }
                note("sent REGISTER with Expires: 0")
            }
            endCall("unregistered", failed = false)
            closeTransport()
            _state.value = PathState.Unregistered
        }
    }

    /**
     * Places a call on the first registered line.
     *
     * The line supplies the credentials, so the call presents that sub-account's identity,
     * and the call builds its own transport and media. One call at a time until the ringing
     * policy is decided.
     */
    /**
     * Places the call, through Android's call stack when that account is enabled, and
     * directly otherwise.
     *
     * Telecom re-enters this class through [VoiceConnectionService], which is the only thing
     * that sends an INVITE. Two ways to reach one path, rather than two paths.
     *
     * The fallback matters: a self-managed account stays disabled until somebody enables it
     * in Settings, and a phone that cannot call until then is worse than one that calls
     * without system integration.
     */
    fun dial(number: String, fromDid: String? = null) {
        if (Provider.normalizeNumber(number).isEmpty()) return fail("Enter a number to dial.")
        val refused = TelecomBridge.placeCall(appContext, Provider.normalizeNumber(number), fromDid)
        if (refused == null) {
            note("placing the call through Android's call stack")
            return
        }
        note("Android will not carry this call ($refused); dialling directly")
        dialOut(number, fromDid)
    }

    fun dialOut(rawNumber: String, fromDid: String? = null) {
        val number = Provider.normalizeNumber(rawNumber)
        if (number.isEmpty()) return fail("Enter a number to dial.")
        if (outbound?.state?.value is PathState.Connected) return fail("A call is already up.")
        val line = lineFor(fromDid)
            ?: return fail(
                if (fromDid == null) {
                    "Choose the number this call should come from."
                } else {
                    "$fromDid is not answering, so the call would go out on a different " +
                        "number. Pick a number that is registered."
                },
            )
        settings.lastNumber = rawNumber.trim()
        val call = Call(
            credentials = line.credentials,
            media = MediaSession(
                note = { text -> note(text) },
                socketFactory = { LocalNetwork.rtpSocket() },
                audioFactory = { log -> CallAudio(appContext, log) },
            ),
            scope = scope,
            note = { text -> note(text) },
        )
        outbound = call
        // The state is mirrored, and the end of the call is the end of the object: its
        // socket and RTP port are resources, not history.
        scope.launch {
            call.state.collect { state ->
                _call.value = state
                if (state is PathState.Ended || state is PathState.Failed) {
                    call.close()
                    if (outbound === call) outbound = null
                }
            }
        }
        // The mock in tools/mock_sip.py has no SRTP stack, so a mock session offers clear
        // PCMU. Real sessions offer RTP/SAVP, which is what the account requires.
        val mode = if (apiSettings.useMock) OfferMode.PCMU_CLEAR else OfferMode.PCMU
        scope.launch { call.dial(number, mode) }
    }

    /**
     * The line a call should go out on.
     *
     * A chosen DID decides it: the number's routing names a sub-account, and the line for
     * that sub-account carries the identity the far end will see. Without a choice, the
     * first registered line is used, which is what a single-number account wants.
     */
    internal fun lineFor(fromDid: String?): Line? {
        val registered = _activeLines.value.filter { it.state.value is LineState.Registered }
        // Never an implicit line. Choosing which number a call comes from is the caller's
        // decision, and answering with a different one is a privacy problem, not a convenience.
        if (fromDid == null) return null
        val account = _dids.value.firstOrNull { it.number == fromDid }?.routedAccount
        // No fallback. Answering with a different line than the one asked for means a call
        // arrives at someone from a number they were not shown, which is worse than refusing.
        return registered.firstOrNull { it.account.equals(account, ignoreCase = true) }
    }

    /**
     * The number a call on [account] would be presented from.
     *
     * Used by the history when a row is tapped: the row names the sub-account, and the
     * dialer needs the line, which is the DID routed at that sub-account. A number that is
     * callable now is preferred over one that merely exists.
     */
    fun didForAccount(account: String?): String? {
        if (account.isNullOrBlank()) return null
        val matching = _dids.value
            .filter { it.routedAccount?.equals(account, ignoreCase = true) == true }
            .map { it.number }
        if (matching.isEmpty()) return null
        // Only a number that can present a call. Handing back one that merely exists made the
        // dialer preselect a line it then could not use, and the engine substituted another.
        val callable = _callableDids.value.toSet()
        return matching.firstOrNull { it in callable }
    }

    /** The DIDs a call can be presented from: chosen, and routed at a registered line. */
    fun numbersToCallFrom(): List<String> {
        val registered = _activeLines.value
            .filter { it.state.value is LineState.Registered }
            .map { it.account.lowercase() }
            .toSet()
        return _dids.value
            .filter { it.routedAccount?.lowercase() in registered }
            .map { it.number }
    }

    /**
     * A call arriving on one of this phone's numbers.
     *
     * Only what the screen needs: who is calling, which number it is for, and which line picked
     * it up. The dialog itself stays inside this class, because answering it is a sequence of
     * SIP messages rather than a state the UI should be able to drive in any order.
     */
    data class Incoming(val caller: String, val did: String, val line: String)

    private val _incoming = MutableStateFlow<Incoming?>(null)
    val incoming: StateFlow<Incoming?> = _incoming

    /** True while Android is showing the incoming call, so this app does not show it too. */
    private val _incomingOnPlatform = MutableStateFlow(false)
    val incomingOnPlatform: StateFlow<Boolean> = _incomingOnPlatform

    /** A dialog that is ringing or answered, held so it can be finished properly. */
    private class IncomingDialog(
        val line: Line,
        val invite: SipMessage,
        val offer: SdpAnswer,
        val caller: String,
        val did: String,
        val tag: String,
        val media: MediaSession,
    ) {
        /** The port the answer advertised, needed again when we send the BYE. */
        var localPort: Int = 0
    }

    private var ringing: IncomingDialog? = null
    private var answered: IncomingDialog? = null
    private var ringTimeout: Job? = null

    private fun newMedia(): MediaSession = MediaSession(
        note = { text -> note(text) },
        socketFactory = { LocalNetwork.rtpSocket() },
        audioFactory = { log -> CallAudio(appContext, log) },
    )

    /**
     * What an arriving request means.
     *
     * The provider only sends requests to a contact that is registered, so an INVITE here is a
     * call for one of these numbers. Everything else in the dialog is the caller finishing a
     * call that already exists, which is why ACK, BYE and CANCEL are handled and nothing else.
     */
    private fun onLineRequest(line: Line, message: SipMessage) {
        when (message.start.method) {
            "INVITE" -> onInvite(line, message)
            "ACK" -> {
                note("the caller acknowledged the answer")
                answerUnacked = false
            }
            "BYE" -> {
                val call = answered ?: ringing
                if (call != null) {
                    runCatching {
                        call.line.send(SipMessage.response(message, 200, "OK", call.tag))
                    }
                }
                endIncoming("the caller hung up")
            }
            "CANCEL" -> {
                val call = ringing
                if (call == null) {
                    // A CANCEL for a call that has already been answered is not a transaction we
                    // have. Answering it 481 stops the retransmissions; answering 200 with a
                    // fresh tag left the caller retrying until it gave up.
                    runCatching {
                        line.send(
                            SipMessage.response(
                                message,
                                481,
                                "Call/Transaction Does Not Exist",
                                line.newTag(),
                            ),
                        )
                    }
                    note("a cancel arrived for a call that was already answered")
                    return
                }
                if (call != null) {
                    runCatching {
                        // 487 terminates the INVITE this CANCEL refers to. Without it the caller
                        // keeps waiting on a dialog nobody is going to answer.
                        call.line.send(
                            SipMessage.response(call.invite, 487, "Request Terminated", call.tag),
                        )
                        call.line.send(SipMessage.response(message, 200, "OK", call.tag))
                    }
                }
                endIncoming("the caller cancelled")
            }
            else -> Unit
        }
    }

    private fun onInvite(line: Line, message: SipMessage) {
        if (ringing != null || answered != null || outbound != null) {
            runCatching {
                line.send(SipMessage.response(message, 486, "Busy Here", line.newTag()))
            }
            note("a call arrived while another was up, so it was refused with 486")
            return
        }
        val offer = Sdp.parse(message.body)
        if (offer == null) {
            runCatching {
                line.send(SipMessage.response(message, 488, "Not Acceptable Here", line.newTag()))
            }
            note("a call arrived with no usable audio stream, so it was refused with 488")
            return
        }
        val caller = callerOf(message.header("From").orEmpty())
        val did = numberIn(message.header("To").orEmpty()).ifBlank { line.account }
        val dialog = IncomingDialog(line, message, offer, caller, did, line.newTag(), newMedia())
        ringing = dialog
        runCatching { line.send(SipMessage.response(message, 180, "Ringing", dialog.tag)) }
        _incoming.value = Incoming(caller, did, line.account)
        note("call from ${caller.ifBlank { "an unknown caller" }} for $did on ${line.account}")
        IncomingAlerts.ringing(appContext, caller, did)
        // Tell the platform as well. Telecom then owns audio routing for this call and shows it,
        // which is the only way the speaker toggle and a Bluetooth headset have anything to act
        // on. A refusal is logged rather than swallowed, since it costs the call its system
        // presence but not the call itself.
        val refused = TelecomBridge.reportIncoming(appContext, caller, did)
        // When the platform took the call it shows its own incoming screen, and ours beside it
        // is a second set of Answer and Decline buttons for one ringing phone.
        _incomingOnPlatform.value = refused == null
        refused?.let { why -> note("the platform would not take the incoming call: $why") }
        // A call that is never answered has to stop ringing. Left alone it rings until the caller
        // gives up, which on a handset means an alarm nobody can silence except by declining.
        ringTimeout?.cancel()
        ringTimeout = scope.launch {
            delay(apiSettings.ringSeconds * 1000L)
            if (ringing === dialog) {
                runCatching {
                    dialog.line.send(
                        SipMessage.response(dialog.invite, 480, "Temporarily Unavailable", dialog.tag),
                    )
                }
                endIncoming("missed: nobody answered in ${apiSettings.ringSeconds} s")
            }
        }
    }

    /** Answers the ringing call, or says why it cannot. */
    fun answerIncoming() {
        val dialog = ringing ?: return
        ringing = null
        ringTimeout?.cancel()
        ringTimeout = null
        IncomingAlerts.stopRinging(appContext)
        val address = LocalNetwork.ipv4()?.address
        val port = runCatching { dialog.media.open() }.getOrNull()
        val ourKey = Sdp.newCryptoKey()
        val sdp = if (address == null || port == null) {
            null
        } else {
            Sdp.answer(dialog.offer, address, port, ourKey)
        }
        if (sdp == null) {
            runCatching {
                dialog.line.send(
                    SipMessage.response(dialog.invite, 488, "Not Acceptable Here", dialog.tag),
                )
            }
            _incoming.value = null
            note("could not answer: the offer has no codec this app decodes")
            return
        }
        // The 200 needs a Contact, which is where the caller sends its ACK, and it must echo any
        // Record-Route so the route set survives. Without the first, the provider ignored our
        // answer: the caller kept ringing while we were already sending media.
        val extra = mutableListOf("Content-Type" to "application/sdp")
        dialog.line.contact()?.let { extra += "Contact" to it }
        for (route in dialog.invite.allHeaders("Record-Route")) extra += "Record-Route" to route
        val answer = SipMessage.response(dialog.invite, 200, "OK", dialog.tag, extra, sdp)
        note("200 OK as sent: " + answer.headers.joinToString(" | ") { "${it.first}: ${it.second.take(70)}" })
        runCatching { dialog.line.send(answer) }
        keepAnsweringUntilAcknowledged(dialog.line, answer)
        val theirs = Sdp.inlineKey(dialog.offer.crypto.firstOrNull().orEmpty())
        val ours = Sdp.inlineKey("crypto:1 AES_CM_128_HMAC_SHA1_80 inline:$ourKey")
        val encrypted = dialog.offer.encrypted && dialog.media.encryptWith(ours, theirs)
        // The caller's media address comes from their offer, which is the one place it exists.
        val remote = dialog.offer.address
            ?.takeIf { it.isNotBlank() && dialog.offer.port > 0 }
            ?.let { InetSocketAddress(it, dialog.offer.port) }
        val audio = if (remote == null) {
            note("the caller's SDP has no media address, so there is no audio path")
            false
        } else {
            dialog.media.begin(scope, remote)
        }
        dialog.localPort = port ?: 0
        answered = dialog
        _incoming.value = null
        _call.value = PathState.Connected(
            number = dialog.caller.ifBlank { dialog.did },
            codec = "PCMU/8000",
            encrypted = encrypted,
            audio = audio,
            at = System.currentTimeMillis(),
        )
        note("answered; media ${if (encrypted) "encrypted" else "in the clear"} to $remote")
    }

    /**
     * True while an answer has been sent and the caller has not acknowledged it.
     *
     * An answer that does not reach the caller leaves them hearing ringback while this side
     * shows a connected call, which is what happened on the handset: the 200 OK went out at
     * 20:04:29 and the ACK came back ten seconds later, after the call had been hung up. Until
     * the ACK arrives the answer is resent, so a lost response costs half a second rather than
     * the call.
     */
    @Volatile
    private var answerUnacked: Boolean = false

    /** Twelve resends at 600 ms is about seven seconds, past the stall that was measured. */
    private val answerResends = 12
    private val answerResendMillis = 600L

    private fun keepAnsweringUntilAcknowledged(line: Line, answer: SipMessage) {
        answerUnacked = true
        scope.launch {
            repeat(answerResends) {
                delay(answerResendMillis)
                if (!answerUnacked) return@launch
                runCatching { line.send(answer) }
            }
        }
    }

    /** Refuses the ringing call. */
    fun declineIncoming() {
        val dialog = ringing ?: return
        ringing = null
        ringTimeout?.cancel()
        ringTimeout = null
        IncomingAlerts.stopRinging(appContext)
        runCatching {
            dialog.line.send(SipMessage.response(dialog.invite, 486, "Busy Here", dialog.tag))
        }
        _incoming.value = null
        note("declined the call from ${dialog.caller.ifBlank { "an unknown caller" }}")
        dialog.media.stop()
    }

    private fun endIncoming(reason: String) {
        ringTimeout?.cancel()
        ringTimeout = null
        val dialog = answered ?: ringing
        ringing = null
        answered = null
        _incoming.value = null
        IncomingAlerts.stopRinging(appContext)
        dialog?.media?.stop()
        if (_call.value is PathState.Connected) _call.value = PathState.Ended(reason)
        else note(reason)
    }

    /**
     * A BYE for an answered incoming call.
     *
     * The dialog runs the other way round from an outbound one: our tag is in the INVITE's `To`,
     * the caller's is in its `From`, and the CSeq is the INVITE's plus one. Getting any of those
     * wrong is a BYE the far end answers `481`.
     */
    private fun byeFor(dialog: IncomingDialog): SipMessage {
        val contact = dialog.invite.header("Contact").orEmpty()
            .substringAfter("<", dialog.invite.header("From").orEmpty())
            .substringBefore(">")
            .substringBefore(";")
        val uri = contact.ifBlank { dialog.invite.header("From").orEmpty().substringAfter("<").substringBefore(">") }
        val cseq = SipMessage.cseqOf(dialog.invite)?.first ?: 1
        val address = LocalNetwork.ipv4()?.address ?: "0.0.0.0"
        // The Via has to name the transport the message leaves on and the port the answer can
        // come back to. It said UDP over a TLS socket and quoted the RTP port, so the provider
        // had nowhere to send its 200 OK and dropped the hang-up instead.
        val headers = mutableListOf(
            "Via" to "SIP/2.0/${dialog.line.transport} $address:${dialog.line.sipLocalPort};" +
                "branch=z9hG4bK${randomHex(10)}",
            "Max-Forwards" to "70",
            // From is the identity we answered as: the INVITE's To, carrying the tag we gave
            // ourselves in the 200 OK. The INVITE's To has no tag at all, and a BYE without the
            // local tag belongs to no dialog, which is why every one of five hang-ups went out
            // and none was answered.
            "From" to identityWithTag(dialog),
            "To" to dialog.invite.header("From").orEmpty(),
            "Call-ID" to dialog.invite.header("Call-ID").orEmpty(),
            "CSeq" to "${cseq + 1} BYE",
            "User-Agent" to SipMessage.USER_AGENT,
        )
        // The route set, reversed as the answering side must. Without it a routed dialog's
        // request never reaches the proxy that is keeping the call up.
        dialog.invite.allHeaders("Record-Route").asReversed().forEach { route ->
            headers += "Route" to route
        }
        return SipMessage.request("BYE", uri.ifBlank { "sip:${dialog.did}@${dialog.line.credentials.host}" }, headers)
    }

    /** The INVITE's To with our local tag, which is what makes a BYE part of the dialog. */
    private fun identityWithTag(dialog: IncomingDialog): String =
        withLocalTag(dialog.invite.header("To").orEmpty(), dialog.tag)

    /** The caller's number from a `From` header, without its display name. */
    private fun callerOf(from: String): String = numberIn(from)

    /** The digits inside `sip:...@host`, which is where a number lives in a URI. */
    private fun numberIn(header: String): String {
        val uri = header.substringAfter("sip:", "").substringBefore("@")
        val digits = uri.filter { it.isDigit() || it == '+' }
        return digits
    }

    fun hangUp() {
        val inbound = answered
        if (inbound != null) {
            // An answered incoming call is ended by us, so the BYE goes from the dialog we
            // accepted. Without this branch Hang up did nothing at all on an inbound call,
            // because the outbound call object is null for one.
            answered = null
            ringTimeout?.cancel()
            ringTimeout = null
            scope.launch {
                // Waiting for the 200 is the difference between the caller's call ending and
                // it carrying on until they give up. It is also what makes the log say which
                // of those happened, instead of a cheerful "hung up" either way.
                val answer = runCatching { inbound.line.request(byeFor(inbound), 4_000) }
                    .getOrNull()
                if (answer == null) {
                    note("the caller never answered the hang-up, so they may still hold the call")
                }
                inbound.media.stop()
                _call.value = PathState.Ended("hung up")
                note("hung up the incoming call")
            }
            return
        }
        val call = outbound ?: return
        scope.launch {
            call.hangUp()
            call.close()
            if (outbound === call) outbound = null
            // The end of the call is published here rather than left to the call's own
            // collector: closing it cancels that collector, and closing happens right after
            // the BYE. Without this the published state stayed at Connected forever, so the
            // call screen never went away and Android was never told the call had ended.
            _call.value = PathState.Ended("hung up")
        }
    }

    /** A keypad press, sent on the live call if there is one. */
    fun sendDtmf(key: Char) {
        outbound?.sendDtmf(key)
    }

    /** The in-call screen's mute, applied to whichever call is live. */
    fun setMuted(on: Boolean) {
        outbound?.setMuted(on)
        _muted.value = on
    }

    /** Records the route the platform reports, so the chip cannot disagree with it. */
    fun noteRouteFromPlatform(on: Boolean) {
        // The platform moved the route, so any device this app pinned a stream to has to go.
        outbound?.followRoute(on)
        answered?.media?.followRoute(on)
        outbound?.noteSpeaker(on)
        answered?.media?.noteSpeaker(on)
    }

    fun setSpeaker(on: Boolean) {
        // Both, deliberately: Telecom holds the route during a self-managed call, and the media
        // layer keeps the flag the screen reads. Asking only the audio manager looked like it
        // worked and changed nothing, because the platform overruled it.
        // Only ask Telecom when Telecom actually holds the route. While the connection exists
        // merely to report the call, its answer is a sentence and not a route.
        val viaPlatform = TelecomBridge.telecomOwnsAudio && TelecomBridge.routeToSpeaker(on)
        if (viaPlatform) {
            // Telecom owns the route, so asking AudioManager as well would be two clients
            // competing for one device. Only the state is recorded here.
            outbound?.noteSpeaker(on)
            answered?.media?.noteSpeaker(on)
        } else {
            outbound?.setSpeaker(on)
            // An answered incoming call has no outbound Call object, so this used to reach
            // nothing at all and the toggle silently did nothing.
            answered?.media?.setSpeaker(on)
        }
        if (viaPlatform) {
            note("speaker ${if (on) "on" else "off"}, routed by Android")
        } else {
            note("speaker ${if (on) "on" else "off"}")
        }
    }

    /** Ends the call, deregisters, and leaves nothing running. */
    fun release() {
        scope.launch {
            refresh?.cancel()
            refresh = null
            endCall("released", failed = false)
            closeTransport()
            _state.value = PathState.Unregistered
        }
    }

    fun warn(text: String) {
        note(text)
    }

    // -------------------------------------------------------------- the API-driven part

    fun saveApi(email: String, password: String) {
        settings.saveApi(email, password)
        note("API credentials saved, sealed with the Keystore")
    }

    fun clearApi() {
        settings.clearApi()
        _accounts.value = emptyList()
        _accountState.value = AccountListState.Idle
        _provision.value = ProvisionState.Idle
        prepared = null
        preparedPassword = null
        note("API credentials removed")
    }

    fun loadAccounts() {
        scope.launch {
            _accountState.value = AccountListState.Loading
            try {
                val list = api.subAccounts()
                _accounts.value = list
                _accountState.value = AccountListState.Loaded
                note("${list.size} sub-account(s) on this VoIP.ms account")
            } catch (fault: Exception) {
                val reason = describe(fault)
                _accountState.value = AccountListState.Failed(reason)
                note("could not list sub-accounts: $reason")
            }
        }
    }

    /**
     * One read of everything the front screen needs, in sequence.
     *
     * Fired together, the two reads competed for an API that answers in about ten seconds,
     * and both timed out at once. In sequence the second one reuses the connection and the
     * screen gets a single failure to report rather than two.
     */
    fun refreshAccount() {
        // Nothing to read with: a debug session can point at the mock before signing in, and
        // an error about a missing key is not a thing the person ticking that box did wrong.
        if (apiSettings.credentials() == null) return
        scope.launch {
            _fetching.value = true
            _fetchError.value = null
            try {
                try {
                    val accounts = api.subAccounts()
                    _accounts.value = accounts
                    note("${accounts.size} sub-account(s) on this account")
                } catch (fault: Exception) {
                    _fetchError.value = describe(fault)
                    note("could not list sub-accounts: ${describe(fault)}")
                }
                try {
                    val list = api.dids()
                    _dids.value = list
                    cacheDids(list)
                    publishReadiness()
                    // The list arriving is what a launch may have been waiting for. Without this
                    // the restore never happened until the next launch.
                    startIfConfigured()
                    settings.checkedAt = System.currentTimeMillis()
                    _checkedAt.value = settings.checkedAt
                    note("${list.size} number(s) on this account")
                    // Anything still selected that has gone deserves saying out loud rather
                    // than quietly ringing nothing.
                    val gone = settings.selectedDids - list.map { it.number }.toSet()
                    if (gone.isNotEmpty()) note("no longer on the account: ${gone.joinToString(", ")}")
                } catch (fault: Exception) {
                    _fetchError.value = describe(fault)
                    note("could not load the numbers: ${describe(fault)}")
                }
            } finally {
                _fetching.value = false
            }
            // If the portal moved a chosen number to a different sub-account, the lines have
            // to follow, or the phone keeps answering a line the number no longer reaches.
            if (_fetchError.value == null && settings.selectedDids.isNotEmpty()) {
                val implied = _dids.value
                    .filter { it.number in settings.selectedDids }
                    .mapNotNull { it.routedAccount }
                    .toSet()
                val registered = _activeLines.value.map { it.account }.toSet()
                if (implied != registered) {
                    note("the routing of a chosen number changed; reconciling the lines")
                    answerOnSelected()
                    return@launch
                }
            }
            // "None of the chosen numbers routes to a sub-account" was true only because
            // the list had not arrived yet, so it must not outlive the list.
            if (_fetchError.value == null) {
                if (_provision.value is ProvisionState.Failed) _provision.value = ProvisionState.Idle
                if (settings.selectedDids.isNotEmpty() && _activeLines.value.isEmpty()) {
                    answerOnSelected()
                }
            }
        }
    }

    /**
     * The launch path.
     *
     * The numbers come from the cache, so the first screen is populated immediately. Only
     * what registration needs is fetched: the sub-account record, because the SIP password
     * is deliberately never stored. The number list is re-read when there is no cache, when
     * the user presses Reload, or on the service's timer, which keeps a stale routing from
     * going unnoticed without paying for two slow reads at every launch.
     */
    fun startUp() {
        if (_dids.value.isEmpty()) {
            // Nothing cached, so this launch has to read the account anyway.
            refreshAccount()
            return
        }
        // Distinct from the restore's own line, which says the same count for a different
        // reason. Two identical sentences 800 ms apart read as a double launch and sent me
        // hunting one that never existed.
        note("launching with ${_dids.value.size} saved number(s); restoring registration")
        // Not reported here: reading whether the account is enabled needs READ_PHONE_NUMBERS,
        // so the state is discovered by attempting a call and logging the outcome there.
        startIfConfigured()
    }

    /**
     * What the lines are actually doing, for the screen.
     *
     * The probe's [state] describes one call. Registration belongs to the lines, and there
     * can be several, so a page that reported `state` said "Not registered" over a phone
     * that was quietly answering. This is the single place that summary is computed.
     */
    data class Readiness(
        val lines: Int = 0,
        val registered: Int = 0,
        val preparing: Boolean = false,
        val failure: String? = null,
        val grantedSeconds: Int = 0,
    )

    /**
     * What the provider says about a line, which is the only fact on this phone that can
     * contradict the line's own opinion.
     */
    sealed interface Agreement {
        data object Unknown : Agreement
        data object Checking : Agreement
        data class Agrees(val server: String?, val transport: String?) : Agreement
        data class Disagrees(val why: String) : Agreement
        data class Unreachable(val why: String) : Agreement
    }

    private val _agreement = MutableStateFlow<Map<String, Agreement>>(emptyMap())
    val agreement: StateFlow<Map<String, Agreement>> = _agreement

    /** Five minutes is well inside any grant, and polite to the provider's rate limit. */
    private val AGREEMENT_INTERVAL_MILLIS = 5 * 60_000L

    /** One check per account per five minutes, because the provider rate limits this call. */
    private val agreementChecked = mutableMapOf<String, Long>()
    private val agreementLock = Any()

    private fun checkProviderFor(account: String) {
        val now = System.currentTimeMillis()
        synchronized(agreementLock) {
            val last = agreementChecked[account] ?: 0L
            if (now - last < AGREEMENT_INTERVAL_MILLIS) return
            agreementChecked[account] = now
        }
        _agreement.update { it + (account.lowercase() to Agreement.Checking) }
        scope.launch {
            val verdict = runCatching { api.registrationStatus(account) }.fold(
                onSuccess = { status ->
                    if (status.registered) {
                        Agreement.Agrees(status.serverHostname, status.transport)
                    } else {
                        Agreement.Disagrees("the provider has no binding for it")
                    }
                },
                onFailure = { fault -> Agreement.Unreachable(describe(fault)) },
            )
            _agreement.update { it + (account.lowercase() to verdict) }
        }
    }

    private val _readiness = MutableStateFlow(Readiness())
    val readiness: StateFlow<Readiness> = _readiness

    /**
     * True until the first registration attempt has produced a line or failed.
     *
     * The screens need this apart from [readiness], because a phone that has not finished
     * starting is not a phone with nothing set up, and saying so is a lie the user reads as
     * a fault.
     */
    private val _starting = MutableStateFlow(true)
    val starting: StateFlow<Boolean> = _starting

    /**
     * The numbers a call can be placed from right now.
     *
     * Derived where readiness is derived, because a screen that computed this itself ended
     * up with a snapshot taken before the registration landed, and so an empty picker.
     */
    private val _callableDids = MutableStateFlow<List<String>>(emptyList())
    val callableDids: StateFlow<List<String>> = _callableDids

    private fun publishReadiness() {
        val all = _activeLines.value
        val states = all.map { it.state.value }
        _readiness.value = Readiness(
            lines = all.size,
            registered = states.count { it is LineState.Registered },
            preparing = states.any { it is LineState.Preparing },
            failure = states.firstNotNullOfOrNull { (it as? LineState.Failed)?.reason },
            grantedSeconds = states.filterIsInstance<LineState.Registered>()
                .minOfOrNull { it.grantedSeconds } ?: 0,
        )
        if (all.isNotEmpty()) _starting.value = false
        // Every line that says it is registered gets a second opinion, cheaply and rarely.
        all.filter { it.state.value is LineState.Registered }
            .forEach { checkProviderFor(it.account) }
        val live = all.filter { it.state.value is LineState.Registered }
            .map { it.account.lowercase() }
            .toSet()
        _callableDids.value = _dids.value
            .filter { it.routedAccount?.lowercase() in live }
            .map { it.number }
    }

    /** Re-registers the chosen numbers after a restart, because a phone should not need a tap. */
    fun startIfConfigured() {
        // "Nothing to restore" is not "starting". Leaving the flag set put a modal loading
        // dialog in front of an app with no numbers chosen, which looked like a hung app and
        // was: nothing was ever going to clear it.
        if (!settings.numbersConfirmed || settings.selectedDids.isEmpty() ||
            _activeLines.value.isNotEmpty()
        ) {
            _starting.value = false
            return
        }
        if (_dids.value.isEmpty()) {
            note("waiting for the number list before restoring the registration")
            _starting.value = false
            return
        }
        note("restoring the chosen numbers after a restart")
        answerOnSelected()
    }

    /** Reads the number list written by the last successful fetch. */
    private fun restoreDids() {
        val cached = settings.didCache
        if (cached.isBlank()) return
        runCatching {
            val array = JSONArray(cached)
            val out = mutableListOf<Did>()
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                out += Did(
                    number = row.optString("did"),
                    description = row.optString("description"),
                    routing = row.optString("routing"),
                    voicemail = row.optString("voicemail"),
                    dialtime = row.optInt("dialtime"),
                )
            }
            if (out.isNotEmpty()) {
                _dids.value = out
                publishReadiness()
                note("${out.size} number(s) from the saved list")
            }
        }.onFailure { note("the saved number list could not be read; it will be fetched") }
    }

    /** Reads the call list written by the last successful fetch. */
    private fun restoreCalls() {
        val cached = settings.callsCache
        if (cached.isBlank()) return
        runCatching {
            val array = JSONArray(cached)
            val out = mutableListOf<CallRecord>()
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                out += CallRecord(
                    date = row.optString("date"),
                    destination = row.optString("destination"),
                    disposition = row.optString("disposition"),
                    description = row.optString("description"),
                    seconds = row.optInt("seconds"),
                    logs = row.optString("logs"),
                    account = row.optString("account"),
                    callerId = row.optString("callerId"),
                )
            }
            if (out.isNotEmpty()) {
                _calls.value = CallsView(records = out)
                note("${out.size} call record(s) from the saved list")
            }
        }.onFailure { note("the saved call list could not be read; it will be fetched") }
    }

    private fun cacheCalls(list: List<CallRecord>) {
        val array = JSONArray()
        for (record in list) {
            array.put(
                JSONObject()
                    .put("date", record.date)
                    .put("destination", record.destination)
                    .put("disposition", record.disposition)
                    .put("description", record.description)
                    .put("seconds", record.seconds)
                    .put("logs", record.logs)
                    .put("account", record.account)
                    .put("callerId", record.callerId),
            )
        }
        settings.callsCache = array.toString()
    }

    private fun cacheDids(list: List<Did>) {
        val array = JSONArray()
        for (did in list) {
            array.put(
                JSONObject()
                    .put("did", did.number)
                    .put("description", did.description)
                    .put("routing", did.routing)
                    .put("voicemail", did.voicemail)
                    .put("dialtime", did.dialtime),
            )
        }
        settings.didCache = array.toString()
    }

    /** Saves the chosen numbers. Nothing else in the app decides which calls it answers. */
    fun selectDids(numbers: Set<String>) {
        settings.selectedDids = numbers
        note("answering ${numbers.size} number(s)")
    }

    /**
     * Asks the provider what it recorded for today's calls.
     *
     * This is the only way to learn why a call the provider accepted did not complete:
     * the INVITE tells us what we sent, and this tells us what happened to it, in the
     * provider's own words.
     */
    fun loadCalls() {
        scope.launch {
            // The saved list stays on screen while this runs: a history that blanks itself
            // on every refresh is a screen the user cannot read.
            _calls.value = _calls.value.copy(loading = true, error = null)
            try {
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val now = System.currentTimeMillis()
                val records = api.callRecords(
                    fromDate = format.format(Date(now - DAY_MILLIS)),
                    toDate = format.format(Date(now)),
                )
                // Every record the window covers, not the first eight. The cap was a
                // display limit that read as "there are no more calls", and it hid a
                // missed call that the log said had been read.
                _calls.value = CallsView(records = records)
                cacheCalls(records)
                settings.callsCheckedAt = System.currentTimeMillis()
                _callsCheckedAt.value = settings.callsCheckedAt
                note("read ${records.size} call record(s) from the provider")
                // Temporary: the history has to be filterable per number, and the line a
                // call used has to come from somewhere in this record. This says where.
                records.firstOrNull { it.description.startsWith("Outbound") }?.let { record ->
                    note("cdr-leg ${record.date} ${record.destination} ${record.seconds}s")
                    for (detail in record.logs.lines().take(12)) {
                        if (detail.isNotBlank()) note("cdr-log ${detail.trim()}")
                    }
                }
            } catch (fault: Exception) {
                // The failure is reported over whatever was already known, not instead of it.
                _calls.value = _calls.value.copy(loading = false, error = describe(fault))
                note("could not read the call records: ${describe(fault)}")
            }
        }
    }

    /**
     * Turns one sub-account into credentials.
     *
     * Order matters. The password is read from the list the API already returned and is
     * only written back when it came back empty, because writing is the step with
     * consequences outside this phone. The server is resolved from the provider's own
     * registration status first, then from its recommended POP, because a sub-account
     * does not name a server and asking the user to know one would be a worse answer.
     */
    fun prepare(id: String) {
        scope.launch {
            runCatching { prepareCredentials(id) }
                .onFailure { fault ->
                    _provision.value = ProvisionState.Failed(describe(fault))
                    note("preparation failed: ${describe(fault)}")
                }
        }
    }

    /**
     * The whole front door in one call.
     *
     * The chosen numbers decide which sub-accounts have to be registered, and several can
     * be live at once because the provider keeps a binding per sub-account. So this
     * prepares and registers every distinct sub-account the chosen numbers route to, and
     * stops any line whose number is no longer chosen.
     */
    fun answerOnSelected() {
        scope.launch {
            try {
                val wanted = settings.selectedDids
                // An empty selection is a decision, not an error: it means answer nothing,
                // so every binding is released rather than a complaint about the tap.
                if (wanted.isEmpty()) {
                    stopAnswering()
                    return@launch
                }
                _provision.value = ProvisionState.Working("checking the numbers")
                if (_accounts.value.isEmpty()) _accounts.value = api.subAccounts()
                if (_dids.value.isEmpty()) {
                    _dids.value = api.dids()
                    cacheDids(_dids.value)
                }
                val routed = _dids.value
                    .filter { it.number in wanted }
                    .mapNotNull { it.routedAccount }
                    .distinct()
                if (routed.isEmpty()) {
                    throw SipFault("none of the chosen numbers routes to a sub-account")
                }
                val subs = routed.mapNotNull { name ->
                    _accounts.value.firstOrNull { it.account.equals(name, ignoreCase = true) }
                }
                if (subs.isEmpty()) throw SipFault("none of those sub-accounts is on the account list")

                val keep = mutableListOf<Line>()
                // One servers list for the whole run: it is a slow call and it does not
                // differ per sub-account.
                val servers = runCatching { api.servers() }.getOrDefault(emptyList())
                for (sub in subs) {
                    val existing = _activeLines.value.firstOrNull { it.subId == sub.id }
                    if (existing != null) {
                        keep += existing
                        continue
                    }
                    _provision.value = ProvisionState.Working("finding the SIP password")
                    val credentials = credentialsFor(sub, servers)
                    _provision.value = ProvisionState.Working("registering")
                    val line = Line(sub.account, sub.id, credentials) { text -> note(text) }
                    // One place decides what an arriving request means. The line owns the
                    // socket and the dialog tags; choosing to ring is not its business.
                    line.onRequest = { message -> onLineRequest(line, message) }
                    line.start(scope)
                    // Reported as the line moves, so the dial page follows a registration
                    // that lands a few seconds after the tap.
                    scope.launch {
                        line.state.collect { publishReadiness() }
                    }
                    keep += line
                }
                // A binding is a resource, so a line whose number is no longer chosen is
                // stopped rather than left registered for nobody.
                _activeLines.value.filterNot { it in keep }.forEach { it.stop() }
                _activeLines.value = keep
                publishReadiness()
                PathfinderService.start(appContext)
                settings.numbersConfirmed = true
                note("answering ${wanted.size} number(s) on ${keep.size} sub-account(s)")
                // The selection can change while this runs, in which case what was
                // registered is no longer what is chosen. Reconcile once more rather than
                // leaving a line up for a number nobody picked.
                if (settings.selectedDids != wanted) {
                    note("the chosen numbers changed during registration; reconciling")
                    answerOnSelected()
                }
                _provision.value = ProvisionState.Idle
            } catch (fault: Exception) {
                _starting.value = false
                _provision.value = ProvisionState.Failed(describe(fault))
                note("could not start answering: ${describe(fault)}")
            }
        }
    }

    /** Stops every line, leaving the account exactly as it was. */
    /**
     * Re-registers everything from the current settings.
     *
     * Needed whenever the server, the port or the transport changes: running lines keep the
     * credentials they were built with, so a new server silently did nothing and the log went
     * on saying atlanta while the screen said tampa.
     */
    fun restartLines() {
        scope.launch {
            stopAnswering()
            // A moment for the released sockets to go, so the re-register cannot race the
            // deregistration of the same account.
            kotlinx.coroutines.delay(400)
            answerOnSelected()
        }
    }

    fun stopAnswering() {
        _activeLines.value.forEach { it.stop() }
        _activeLines.value = emptyList()
        publishReadiness()
        PathfinderService.stop(appContext)
        note("stopped answering; every registration was released")
    }

    /**
     * Credentials for one sub-account, without disturbing the probe's prepared state.
     *
     * The password may have to be generated and written back, which is the one write with
     * consequences outside this phone, so the value is verified before it is used. The
     * server comes from the provider's own registration status first and from the POP it
     * recommends second, as everywhere else.
     */
    private suspend fun credentialsFor(sub: SubAccount, servers: List<ServerPop>): SipCredentials {
        var working = sub
        var password = sub.password
        if (password.isNullOrBlank()) {
            val fresh = Provisioning.generatePassword()
            note("the API returned no password for ${sub.account}; writing a generated one")
            api.setSubAccount(Provisioning.updateParams(sub, fresh))
            working = api.subAccounts().firstOrNull { it.id == sub.id }
                ?: throw SipFault("${sub.account} disappeared after the password update")
            if (working.password != fresh) {
                throw SipFault("the provider did not keep the generated password for ${sub.account}")
            }
            password = fresh
            note("the generated password was accepted for ${sub.account}")
        }
        val known = password.orEmpty().ifEmpty {
            throw SipFault("no SIP password for ${sub.account}")
        }
        // The expiry the app registers with is whatever the account allows. It is
        // deliberately not adjusted here: a client that edits account limits to suit itself
        // is a client nobody can reason about, and the one time this mattered was a leftover
        // from our own test, which belongs in the portal, not in the code.
        val status = runCatching { api.registrationStatus(working.account) }.getOrNull()
            ?: RegistrationStatus(false, null, null, null, null)
        val host = settings.serverOverride.ifBlank {
            Provisioning.chooseServer(status, servers)
                ?: throw SipFault("no SIP server could be found for ${sub.account}")
        }
        // The account decides this, not a toggle. A sub-account with Encrypted SIP Traffic on
        // registers over TLS; registering it over UDP succeeds and then cannot take a call,
        // because the provider will not deliver over a transport the account does not allow.
        // The setting can only add TLS to an account that does not require it.
        val tls = working.sipTraffic || settings.useTls
        if (working.sipTraffic && !settings.useTls) {
            note("${working.account} requires encrypted SIP, so this line uses TLS whatever the setting says")
        }
        return Provisioning.credentials(working, known, host, settings.portFor(tls), tls)
    }

    /** The body of [prepare], suspend and throwing, so a caller can chain a registration. */
    private suspend fun prepareCredentials(id: String): SipCredentials {
            _provision.value = ProvisionState.Working("reading the sub-account")
                val list = api.subAccounts()
                _accounts.value = list
                var sub = list.firstOrNull { it.id == id }
                    ?: throw SipFault("that sub-account is no longer on the account")
                var generated = false
                var password = sub.password
                if (password.isNullOrBlank()) {
                    val fresh = Provisioning.generatePassword()
                    note("the API returned no password for ${sub.account}; writing a generated one")
                    _provision.value = ProvisionState.Working("writing a generated password")
                    api.setSubAccount(Provisioning.updateParams(sub, fresh))
                    val verified = api.subAccounts().firstOrNull { it.id == id }
                        ?: throw SipFault("the sub-account disappeared after the password update")
                    if (verified.password != fresh) {
                        throw SipFault("the provider did not keep the generated password")
                    }
                    note("the generated password was accepted; it is held in memory only")
                    sub = verified
                    password = fresh
                    generated = true
                }
                val known = password.orEmpty().ifEmpty {
                    throw SipFault("no SIP password could be read or written")
                }

                _provision.value = ProvisionState.Working("finding the SIP server")
                val status = runCatching { api.registrationStatus(sub.account) }
                    .onFailure { note("registration status unavailable: ${describe(it)}") }
                    .getOrNull() ?: RegistrationStatus(false, null, null, null, null)
                val servers = runCatching { api.servers() }.getOrDefault(emptyList())

                val override = settings.serverOverride
                val source = when {
                    override.isNotBlank() -> "the server you typed"
                    status.serverHostname != null -> "the provider's registration status"
                    servers.any { it.recommended } -> "the POP the provider recommends"
                    servers.isNotEmpty() -> "the first POP the provider listed"
                    else -> "nothing: no server could be found"
                }
                val host = override.ifBlank {
                    Provisioning.chooseServer(status, servers)
                        ?: throw SipFault("no SIP server could be found; type one above and try again")
                }

                val tls = settings.useTls
                val result = Provisioning.credentials(sub, known, host, settings.portFor(tls), tls)
                prepared = sub
                preparedPassword = known
                settings.selectedAccountId = sub.id
                _provision.value = ProvisionState.Ready(
                    credentials = result,
                    detail = ProvisionDetail(
                        generatedPassword = generated,
                        serverSource = source,
                        registered = status.registered,
                        registeredOn = listOfNotNull(status.serverHostname, status.registerIp)
                            .joinToString(" "),
                        transport = status.transport ?: "unknown",
                        sipTraffic = sub.sipTraffic,
                        codecs = sub.allowedCodecs,
                        maxExpiry = sub.maxExpiry,
                    ),
                )
                note("prepared ${result.username} at ${result.host}:${result.port}, codecs ${sub.allowedCodecs}")
                if (sub.sipTraffic) {
                    note("sip_traffic is on for this sub-account; plain UDP registration may be refused")
                }
                return result
    }

    /**
     * Adds `g722` to the sub-account's allowed codecs, preserving everything else.
     *
     * This is one of only two things that writes to the account, and it is a button rather
     * than a default because it changes what every device on the account may negotiate,
     * not only this probe. The update is built from the freshly read record, so it cannot
     * clear a required setting it did not name.
     */
    fun enableG722() {
        val sub = prepared ?: return note("prepare a sub-account before changing its codecs")
        writeCodecs(Provisioning.withCodec(sub.allowedCodecs, Provisioning.G722), "adding g722")
    }

    /**
     * Moves `g722` to the front of the allowed codecs.
     *
     * The provider builds its answer from this list, not from our SDP order: on 2026-11-09
     * an offer of `9 0 101` came back as `0 9 101` while the account allowed
     * `ulaw;g729;g722`. So this is the call that separates "the server prefers PCMU" from
     * "the server follows whatever order the account gives it".
     */
    fun preferG722() {
        val sub = prepared ?: return note("prepare a sub-account before changing its codecs")
        writeCodecs(
            Provisioning.withCodecFirst(sub.allowedCodecs, Provisioning.G722),
            "moving g722 to the front",
        )
    }

    /**
     * Turns Encrypted SIP Traffic on or off for the prepared sub-account.
     *
     * It is the single setting that decides whether media is SRTP or in the clear, and the
     * provider applies it to the account rather than to the client: with it off, a
     * sub-account answers even an encrypted offer with plain RTP. Which is why flipping it
     * from here is the fastest way between the two worlds, and why the read-back matters.
     */
    fun setEncryptedSipTraffic(on: Boolean) {
        val sub = prepared ?: return note("prepare a sub-account before changing it")
        if (sub.sipTraffic == on) {
            note("Encrypted SIP Traffic is already ${if (on) "on" else "off"}")
            return
        }
        writeSubAccount(
            sub.copy(sipTraffic = on),
            if (on) "turning encrypted SIP traffic on" else "turning encrypted SIP traffic off",
        )
    }

    /**
     * Writes the sub-account, then lets the read-back decide whether it took.
     *
     * A write that times out says nothing about the write itself. Both attempts on
     * 2026-11-09 timed out at 30 s and the change had to be made in the portal, so the
     * response here is not treated as evidence. The next read is.
     */
    private fun writeCodecs(updated: String, what: String) {
        val sub = prepared ?: return note("prepare a sub-account before changing its codecs")
        if (updated == sub.allowedCodecs) {
            note("the sub-account already allows ${sub.allowedCodecs}")
            return
        }
        writeSubAccount(sub.copy(allowedCodecs = updated), what)
    }

    private fun writeSubAccount(updated: SubAccount, what: String) {
        scope.launch {
            val password = preparedPassword
            if (password == null) {
                note("prepare a sub-account before changing it")
                return@launch
            }
            _provision.value = ProvisionState.Working(what)
            note(
                "writing ${updated.id}: codecs ${updated.allowedCodecs}, encrypted SIP " +
                    "${if (updated.sipTraffic) "on" else "off"}, expiry ${updated.maxExpiry} s, " +
                    "canada routing ${updated.canadaRouting}, international ${updated.internationalRoute}",
            )
            var writeFault: Exception? = null
            try {
                api.setSubAccount(Provisioning.updateParams(updated, password))
            } catch (fault: Exception) {
                writeFault = fault // the write may still have landed, so carry on
            }
            writeFault?.let { note("the write did not answer (${describe(it)}), so the read decides") }

            // The record can lag the write, so one read is not evidence either. Three
            // reads, a second and a half apart, and the last one decides. This is what
            // tells "slow to apply" apart from "accepted and ignored".
            var latest: SubAccount? = null
            for (attempt in 1..READ_BACK_ATTEMPTS) {
                if (attempt > 1) delay(READ_BACK_DELAY_MILLIS)
                latest = try {
                    api.subAccounts().firstOrNull { it.id == updated.id }
                } catch (fault: Exception) {
                    note("read-back attempt $attempt failed: ${describe(fault)}")
                    null
                }
                if (latest != null && kept(latest, updated)) break
            }
            val verified = latest
            if (verified == null) {
                _provision.value = ProvisionState.Failed("the sub-account could not be read back")
                note("the sub-account could not be read back, so its state is unknown")
                return@launch
            }
            if (kept(verified, updated)) {
                note(
                    "the sub-account now allows ${verified.allowedCodecs}, encrypted SIP " +
                        "${if (verified.sipTraffic) "on" else "off"}, expiry ${verified.maxExpiry} s",
                )
                prepare(updated.id)
            } else {
                // Name every field that was compared. An earlier version reported only the
                // codecs and the encryption, so a mismatch on the expiry read as though the
                // codecs had been refused, which sent us looking in the wrong place.
                val message = "the provider kept codecs ${verified.allowedCodecs}, encrypted SIP " +
                    "${if (verified.sipTraffic) "on" else "off"} and expiry ${verified.maxExpiry} s; " +
                    "sent codecs ${updated.allowedCodecs}, encrypted SIP " +
                    "${if (updated.sipTraffic) "on" else "off"} and expiry ${updated.maxExpiry} s"
                _provision.value = ProvisionState.Failed(message)
                note(message)
                note(
                    "the write was accepted and the field did not change after " +
                        "${READ_BACK_ATTEMPTS} reads, so this is the API ignoring it rather than lag",
                )
            }
        }
    }

    /** Whether the provider kept the fields this app writes. */
    private fun kept(actual: SubAccount, wanted: SubAccount): Boolean =
        actual.allowedCodecs.equals(wanted.allowedCodecs, ignoreCase = true) &&
            actual.sipTraffic == wanted.sipTraffic &&
            actual.maxExpiry == wanted.maxExpiry

    // ------------------------------------------------------------------------ logging

    /** One line in the same log the screens show, for code outside this class. */
    internal fun noteFromElsewhere(text: String) = note(text)

    private fun note(text: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(lines) {
            for (line in text.trimEnd().split("\n")) lines.addLast("$stamp  $line")
            while (lines.size > MAX_LOG_LINES) lines.removeFirst()
            _log.value = lines.toList()
        }
        // Also to logcat, because a secondary Android user's files are unreadable to adb
        // while logcat is not. Without this the only way to see a run from another profile
        // is to retype it by hand.
        for (line in text.trimEnd().split("\n")) Log.i(LOGCAT_TAG, "$stamp  $line")
    }

    private fun fail(reason: String) {
        note(reason)
        _state.value = PathState.Failed(reason)
    }

    /**
     * The log is meant to be copied into notes, so the digest response goes. It is a
     * hash, not the password, but with the nonce and URI beside it in the same file it
     * is one less thing to think about.
     */
    private fun redact(text: String): String = text
        .replace(Regex("response=\"[0-9a-fA-F]{32}\""), "response=\"<redacted>\"")
        .replace(Regex("(password=)\"[^\"]*\""), "$1\"<redacted>\"")

    // ---------------------------------------------------------------------- transport

    private fun openTransport(credentials: SipCredentials) {
        val binding = LocalNetwork.ipv4()
            ?: throw SipFault("no IPv4 address; connect Wi-Fi or cellular first")
        val link = if (credentials.tls) {
            TlsLink(credentials.host, credentials.port)
        } else {
            val target = InetAddress.getByName(credentials.host)
            UdpLink(DatagramSocket().apply { soTimeout = 0 }, InetSocketAddress(target, credentials.port))
        }

        sipLink = link
        localSipPort = link.localPort
        localIp = binding.address
        callId = "${randomHex(10)}@$localIp"
        fromTag = randomHex(8)
        cseq = randomInt(1, 5000)

        note("local $localIp:${link.localPort} via ${binding.interfaceName}")
        note("${link.transport} to ${credentials.host}:${credentials.port}")
        sipReceiver = scope.launch { receiveSip(link) }
        if (link is TlsLink) {
            // A TLS connection behind a NAT needs traffic to survive the idle timer.
            // Registration refresh is measured in minutes, which is far too slow.
            keepalive = scope.launch {
                while (isActive) {
                    delay(KEEPALIVE_MILLIS)
                    runCatching { link.keepalive() }
                }
            }
        }
    }

    private fun closeTransport() {
        sipReceiver?.cancel()
        sipReceiver = null
        keepalive?.cancel()
        keepalive = null
        pending.clear()
        sipLink?.close()
        sipLink = null
        registered = false
    }

    private fun CoroutineScope.receiveSip(link: SipLink) {
        while (isActive) {
            val incoming = link.receive()
            if (incoming == null) {
                if (isActive) note("SIP connection closed")
                return
            }
            val message = SipMessage.parse(incoming.bytes)
            if (message == null) {
                note("unparsable ${incoming.bytes.size} bytes")
                continue
            }
            note("<<< ${redact(String(message.toBytes(), Charsets.ISO_8859_1))}")
            try {
                handle(message, incoming.source)
            } catch (fault: Exception) {
                note("handling a SIP message failed: ${describe(fault)}")
            }
        }
    }

    private fun handle(message: SipMessage, source: InetSocketAddress?) {
        if (message.start.isRequest) {
            handleRequest(message, source)
            return
        }
        val cseq = SipMessage.cseqOf(message)
        if (cseq == null) {
            note("response without a usable CSeq: ${message.start.firstLine}")
            return
        }
        val channel = pending["${cseq.first} ${cseq.second}"]
        if (channel == null) {
            note("no transaction for ${cseq.first} ${cseq.second}, ignoring it")
            return
        }
        channel.trySend(message)
    }

    private fun handleRequest(message: SipMessage, source: InetSocketAddress?) {
        when (message.start.method) {
            "OPTIONS" -> reply(message, source, 200, "OK")
            "BYE" -> {
                reply(message, source, 200, "OK")
                note("the far end hung up")
                endCall("the far end hung up", failed = false)
            }
            "INVITE" -> {
                // An incoming call is not answered in this phase. Replying 486 is still
                // the proof that the registration is live and reachable, which no
                // status line produced by our own registration can show.
                val count = _answers.value.incomingInvites + 1
                _answers.update { it.copy(incomingInvites = count) }
                note("incoming INVITE #$count from ${message.header("From")}")
                note("this build answers 486 on purpose; incoming calls are Phase B")
                reply(message, source, 486, "Busy Here")
            }
            "CANCEL" -> reply(message, source, 481, "Call/Transaction Does Not Exist")
            else -> reply(message, source, 405, "Method Not Allowed")
        }
    }

    private fun reply(request: SipMessage, source: InetSocketAddress?, code: Int, reason: String) {
        val response = SipMessage.replyingTo(request, code, reason, toTag = randomHex(6))
        send(response, source)
    }

    private fun send(message: SipMessage) {
        send(message, null)
    }

    private fun send(message: SipMessage, target: InetSocketAddress?) {
        val link = sipLink ?: throw SipFault("the SIP transport is closed")
        val bytes = message.toBytes()
        note(">>> ${redact(String(bytes, Charsets.ISO_8859_1))}")
        link.send(bytes, target)
    }

    /**
     * One request/response exchange, with the retransmission UDP needs.
     *
     * A provisional response stops the resends, which is what RFC 3261 requires: after
     * one, the caller waits and the server owns the timers. Without that rule a call
     * rings at the far end while this side INVITEs it again every four seconds.
     */
    private suspend fun transact(
        request: SipMessage,
        timeoutMillis: Long,
        onProvisional: (SipMessage) -> Unit = {},
    ): SipMessage? {
        val cseq = SipMessage.cseqOf(request) ?: throw SipFault("request without CSeq")
        val key = "${cseq.first} ${cseq.second}"
        val channel = Channel<SipMessage>(Channel.UNLIMITED)
        pending[key] = channel
        val deadline = System.currentTimeMillis() + timeoutMillis
        var interval = FIRST_RETRANSMIT_MILLIS
        var heard = false
        try {
            send(request)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    note("$key timed out after ${timeoutMillis / 1000} s")
                    return null
                }
                val wait = if (heard) remaining else minOf(interval, remaining)
                val next = withTimeoutOrNull(wait) { channel.receive() }
                if (next == null) {
                    // A reliable stream owns its own delivery, so re-sending over it
                    // duplicates the request rather than recovering anything.
                    if (heard || sipLink?.reliable == true) {
                        note("$key timed out after ${timeoutMillis / 1000} s")
                        return null
                    }
                    send(request)
                    interval = minOf(interval * 2, MAX_RETRANSMIT_MILLIS)
                    continue
                }
                val status = next.start.status ?: continue
                if (status in 100..199) {
                    heard = true
                    onProvisional(next)
                    continue
                }
                return next
            }
        } finally {
            pending.remove(key)
        }
    }

    private fun nextCSeq(): Int = synchronized(cseqLock) { ++cseq }

    private fun branch(): String = "z9hG4bK" + randomHex(10)

    private fun via(branch: String): String {
        val link = sipLink ?: throw SipFault("the SIP transport is closed")
        val rport = if (link.reliable) "" else ";rport"
        return "SIP/2.0/${link.transport} $localIp:$localSipPort;branch=$branch$rport"
    }

    private fun randomHex(count: Int): String {
        val bytes = ByteArray(count)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomInt(from: Int, to: Int): Int = from + random.nextInt(to - from + 1)

    // --------------------------------------------------------------------------- calls

    // --------------------------------------------------------------------------- media

    /**
     * What the answer means, and whether this build can carry it.
     *
     * Today only one outcome is reachable, and it is not a call:
     *
     * 1. Encrypted media, which is every answer while `sip_traffic` is on. The negotiation
     *    is the answer to open question 1, the codec named in the `m=` line is the answer
     *    to open question 2, and the call is silent because decrypting needs the SRTP
     *    layer this phase has not built.
     * 2. PCMU in the clear, or another codec, both unreachable for now. They stay because
     *    they are what a call looks like once SRTP lands and the audio path matters again.
     */
    // ------------------------------------------------------------------- registration

    private fun requestRegister(
        credentials: SipCredentials,
        expires: Int,
        authorization: Pair<String, String>?,
        cseqValue: Int,
    ): SipMessage {
        val identity = Provider.userUri(credentials.host, credentials.username)
        val headers = mutableListOf(
            "Via" to via(branch()),
            "Max-Forwards" to "70",
            "From" to "<$identity>;tag=$fromTag",
            "To" to "<$identity>",
            "Call-ID" to callId,
            "CSeq" to "$cseqValue REGISTER",
            "Contact" to Provider.contactUri(
                credentials.host,
                credentials.username,
                localIp,
                localSipPort,
                if (credentials.tls) "tls" else null,
            ),
            "Expires" to expires.toString(),
            "User-Agent" to SipMessage.USER_AGENT,
        )
        authorization?.let { headers += it }
        return SipMessage.request("REGISTER", Provider.serverUri(credentials.host), headers)
    }

    private suspend fun doRegister(credentials: SipCredentials): Int {
        // The registrar grants the lesser of this and its own ceiling, so asking for a short
        // expiry is a way to measure the lapse in minutes without touching the account.
        val requested = expiryRequest ?: credentials.expiresSeconds
        var authorization: Pair<String, String>? = null
        var cseqValue = nextCSeq()

        repeat(3) { attempt ->
            val request = requestRegister(credentials, requested, authorization, cseqValue)
            val response = transact(request, timeoutMillis = REGISTER_TIMEOUT_MILLIS)
                ?: throw SipFault(
                    "no answer to REGISTER; UDP to ${credentials.host}:${credentials.port} is not getting through",
                )
            when (val status = response.start.status ?: -1) {
                200 -> {
                    val granted = grantedExpiry(response, requested)
                    _answers.update { it.copy(grantedSeconds = granted) }
                    note("registered, granted $granted s")
                    return granted
                }
                401, 407 -> {
                    val challengeName = if (status == 401) "WWW-Authenticate" else "Proxy-Authenticate"
                    val headerName = if (status == 401) "Authorization" else "Proxy-Authorization"
                    val value = response.header(challengeName)
                        ?: throw SipFault("$status without a $challengeName header")
                    val challenge = Digest.parse(value)
                        ?: throw SipFault("$challengeName is not Digest: ${value.take(48)}")
                    authorization = headerName to Digest.authorization(
                        challenge = challenge,
                        username = credentials.username,
                        password = credentials.password,
                        method = "REGISTER",
                        uri = Provider.serverUri(credentials.host),
                        cnonce = randomHex(8),
                    )
                    cseqValue = nextCSeq()
                    note("$status challenge, answering with $headerName")
                    if (attempt == 2) {
                        throw SipFault("the server keeps answering $status; check the username and password")
                    }
                }
                else -> throw SipFault("REGISTER answered $status ${response.start.reason}")
            }
        }
        throw SipFault("could not register")
    }

    /** What the server actually granted, which is not always what was asked for. */
    private fun grantedExpiry(response: SipMessage, requested: Int): Int {
        response.header("Expires")?.trim()?.toIntOrNull()?.let { if (it > 0) return it }
        response.header("Contact")?.let { contact ->
            Regex("expires=(\\d+)", RegexOption.IGNORE_CASE)
                .find(contact)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { if (it > 0) return it }
        }
        return requested
    }

    /**
     * Stops refreshing on purpose and watches the provider's own view until it lapses.
     *
     * This is the measurement behind open question 3, and the only Phase 0 answer that
     * cannot be had from a single call: the grant is 3600 s, we refresh at 80% of it, and
     * nobody has measured how long the provider keeps believing a client that has gone
     * quiet. It is passive, so it costs one tap and an idle phone rather than attention.
     */
    fun measureLapse() {
        val current = credentials ?: return note("register before measuring the lapse")
        val registered = _state.value as? PathState.Registered
            ?: return note("register before measuring the lapse")
        val granted = registered.grantedSeconds
        scope.launch {
            refresh?.cancel()
            refresh = null
            val stopped = System.currentTimeMillis()
            // The binding is counted from the REGISTER, not from when refreshing stopped, so
            // that is the anchor for every number here. Measuring from the stop made the
            // first run of this report a lapse of 26 s against a 60 s grant, which reads as
            // an early drop and was really 60 s from the registration.
            val expiresAt = registered.at + granted * 1000L
            val already = ((stopped - registered.at) / 1000).toInt()
            note(
                "refreshing stopped at ${clock(stopped)}, $already s into a $granted s binding; " +
                    "the provider should drop us at ${clock(expiresAt)}",
            )
            while (isActive) {
                delay(LAPSE_POLL_MILLIS)
                val status = try {
                    api.registrationStatus(current.username)
                } catch (fault: Exception) {
                    note("lapse check failed: ${describe(fault)}")
                    continue
                }
                val sinceRegister = ((System.currentTimeMillis() - registered.at) / 1000).toInt()
                if (!status.registered) {
                    note(
                        "LAPSE: the provider dropped the binding ${sinceRegister} s after the " +
                            "REGISTER against a grant of $granted s, which is " +
                            "${sinceRegister - granted} s past its expiry. That is the number the " +
                            "product's keepalive margin has to survive.",
                    )
                    return@launch
                }
                note("still registered ${sinceRegister} s after the REGISTER")
            }
        }
    }

    private fun clock(at: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))

    /**
     * Refreshes at 80% of the grant, and never less than half a minute. The interval is
     * the number behind open question 3: it is what the registration costs in battery
     * for as long as the app is expected to be reachable.
     */
    private fun scheduleRefresh(credentials: SipCredentials, granted: Int) {
        refresh?.cancel()
        refresh = scope.launch {
            var wait = maxOf(MIN_REFRESH_SECONDS, (granted * 0.8).toLong())
            _answers.update { it.copy(refreshSeconds = wait) }
            note("refresh every $wait s")
            while (isActive) {
                delay(wait * 1000)
                try {
                    val again = doRegister(credentials)
                    if (again != granted) note("the server now grants $again s")
                    wait = maxOf(MIN_REFRESH_SECONDS, (again * 0.8).toLong())
                    _answers.update { it.copy(refreshSeconds = wait) }
                    registered = true
                    _state.value = PathState.Registered(again, System.currentTimeMillis())
                } catch (fault: Exception) {
                    registered = false
                    note("registration refresh failed: ${describe(fault)}")
                    _state.value = PathState.RegistrationFailed(describe(fault))
                    return@launch
                }
            }
        }
    }


    /**
     * BYE, with one retry if it is challenged. Ending the local call is not conditional
     * on the server answering: the audio and the socket are ours to stop, and a probe
     * that hangs on a lost BYE is worse than one that leaves a stale dialog at the far
     * end, which the provider's own timeout clears.
     */
    private fun endCall(reason: String, failed: Boolean) {
        // The counters belong in the log, not only in the report file: a run on a secondary
        // Android user cannot be read over adb from its files, and logcat is the channel
        // that works from every profile.
        outbound?.let { note("media totals: ${it.mediaStats.value.framesSent} sent, ${it.mediaStats.value.framesReceived} received") }
        currentInvite = null
        dialog = null
        if (reason.isNotEmpty()) note(reason)
        _state.value = if (failed) PathState.Failed(reason) else PathState.Ended(reason)
    }

    // --------------------------------------------------------------------------- report

    /**
     * Writes the report and the log into this app's own files directory and returns the
     * directory, so a machine with adb can pull them:
     *
     *     adb pull /sdcard/Android/data/com.willingtocode.voicepathfinder/files/runs
     *
     * This exists because reading a SIP exchange off a screenshot is both lossy and the
     * wrong way to handle anything that names an account. The files hold the SIP
     * identities of the call, the way any SIP log does, and no password.
     */
    fun export(): String? {
        val directory = File(appContext.getExternalFilesDir(null), "runs")
        val body = report()
        // Straight to logcat as well, so `adb logcat -s VoicePathfinder` carries the whole
        // report out of a profile whose files adb cannot read.
        for (line in body.split("\n")) Log.i(LOGCAT_TAG, "report  $line")
        return runCatching {
            directory.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(directory, "report-$stamp.txt").writeText(body)
            File(directory, "log-$stamp.txt").writeText(
                synchronized(lines) { lines.joinToString("\n") },
            )
            note("saved the report and log under ${directory.absolutePath}")
            directory.absolutePath
        }.onFailure {
            note("could not save the report: ${describe(it)}")
        }.getOrNull()
    }

    /**
     * The finding, in a form that can be pasted into `notes/STATUS.md`.
     *
     * Every line is something the run actually reported, and a line with no value says so
     * rather than guessing. The SIP password is not here, and neither is the API password:
     * a report is for sharing.
     */
    fun report(): String {
        val answers = _answers.value
        val counters = stats.value
        val detail = (_provision.value as? ProvisionState.Ready)?.detail
        val prepared = prepared
        val builder = StringBuilder()
        fun line(text: String) {
            builder.append(text).append('\n')
        }
        line("Voice Pathfinder report")
        line(
            "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT})",
        )
        line("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        line(
            "sub-account: " + if (prepared != null) {
                "${prepared.label} (${prepared.account}), codecs ${prepared.allowedCodecs}, " +
                    "caller ${prepared.callerId.ifBlank { "none" }}, " +
                    "dialing ${Provisioning.dialingModeLabel(prepared.dialingMode)}"
            } else {
                "-"
            },
        )
        line("sip_traffic: ${if (detail == null) "-" else if (detail.sipTraffic) "on" else "off"}")
        line("server: ${credentials?.let { "${it.host}:${it.port} ${it.transport}" } ?: "-"}")
        line("server source: ${detail?.serverSource ?: "-"}")
        line(
            "credentials: " + when {
                detail == null -> "-"
                detail.generatedPassword -> "password generated and written back by this app"
                else -> "password read from the API, not stored"
            },
        )
        line("state: ${stateText(_state.value)}")
        line("offered: ${answers.offered.ifEmpty { "-" }}")
        line(
            "registration: " + if (answers.grantedSeconds > 0) {
                "granted ${answers.grantedSeconds} s, refreshing every ${answers.refreshSeconds} s"
            } else {
                "not registered"
            },
        )
        line("provider registration status: ${detail?.let { if (it.registered) "registered ${it.registeredOn}" else "not registered elsewhere" } ?: "-"}")
        line("provider transport: ${detail?.transport ?: "-"}")
        line("media protocol: ${answers.protocol.ifEmpty { "-" }}")
        line(
            "media encrypted: " + when {
                answers.protocol.isEmpty() -> "unknown"
                answers.encrypted -> "YES (${answers.cryptoLines} crypto line(s))"
                else -> "no"
            },
        )
        line(
            "srtp: " + if (outbound?.encrypted == true) {
                "${Srtp.SUITE}, ${Srtp.TAG_BYTES} byte tags, decrypting both ways"
            } else {
                "not in use"
            },
        )
        line(
            "negotiated: ${answers.negotiated.ifEmpty { "-" }} " +
                "(payloads ${answers.payloadTypes.ifEmpty { "-" }}, ptime ${answers.ptime ?: "-"})",
        )
        line("remote media: ${answers.remoteMedia.ifEmpty { "-" }}")
        line("INVITE to 200 OK: ${if (answers.inviteMillis >= 0) "${answers.inviteMillis} ms" else "-"}")
        line("incoming INVITEs seen: ${answers.incomingInvites}")
        line(
            "media: ${counters.framesSent} sent, ${counters.framesReceived} received, " +
                "${counters.lost} lost, ${counters.late} late, ${counters.duplicates} duplicated, " +
                "${counters.reordered} reordered",
        )
        // Datagrams and rejections are what tell "the far end sent nothing" apart from
        // "packets arrived and we refused them". Without both, silence is unattributable.
        line(
            "rtp datagrams: ${outbound?.receivedDatagrams ?: 0}, srtp rejected: ${outbound?.rejected ?: 0}",
        )
        line("audio: ${if (counters.echoCancelled) "echo cancellation on" else "echo cancellation off"}")

        val records = _calls.value.records
        if (records.isNotEmpty()) {
            line("provider call records:")
            for (record in records.take(5)) {
                line("  ${record.date}  ${record.disposition}  ${record.description}")
                for (detail in record.logs.lines().take(6)) {
                    if (detail.isNotBlank()) line("    ${detail.trim()}")
                }
            }
        }
        return builder.toString()
    }

    private fun stateText(state: PathState): String = when (state) {
        PathState.Unregistered -> "not registered"
        is PathState.Registering -> "registering"
        is PathState.Registered -> "registered"
        is PathState.RegistrationFailed -> "registration failed: ${state.reason}"
        is PathState.Dialing -> "dialing ${state.number}"
        is PathState.Ringing -> "ringing ${state.number}"
        is PathState.Connected -> if (state.audio) "connected" else "connected without audio"
        is PathState.Ended -> "ended: ${state.reason}"
        is PathState.Failed -> "failed: ${state.reason}"
    }

    /**
     * The saved lists are read here, at the end of the class, because Kotlin runs
     * initializers in declaration order: from above these fields it read nulls, which is
     * how a restore threw and an app crashed on the IO dispatcher.
     */
    init {
        restoreDids()
        restoreCalls()
        publishReadiness()
    }

    private companion object {
        const val MAX_LOG_LINES = 600
        const val FIRST_RETRANSMIT_MILLIS = 500L
        const val MAX_RETRANSMIT_MILLIS = 4_000L
        const val REGISTER_TIMEOUT_MILLIS = 16_000L
        const val INVITE_TIMEOUT_MILLIS = 45_000L
        const val BYE_TIMEOUT_MILLIS = 6_000L

        /** The logcat tag, so a run can be read from another Android user over adb. */
        const val LOGCAT_TAG = "VoicePathfinder"

        /** How often the lapse measurement asks the provider what it thinks. */
        const val LAPSE_POLL_MILLIS = 5_000L

        /** Reads after an account write: the record can lag the write by a second or two. */
        const val READ_BACK_ATTEMPTS = 3
        const val READ_BACK_DELAY_MILLIS = 1_500L
        const val MIN_REFRESH_SECONDS = 30L
        const val STATS_MILLIS = 250L
        const val KEEPALIVE_MILLIS = 25_000L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

