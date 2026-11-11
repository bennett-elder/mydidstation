package com.willingtocode.voicepathfinder

import android.net.Uri
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One call, as Telecom sees it.
 *
 * This does not place the call. It reports one: [Pathfinder.dialOut] is still the only thing
 * that sends an INVITE, and this mirrors the state it publishes into the states Telecom
 * understands, which is what makes the system show the call and hand us audio focus.
 */
internal class VoiceConnection(
    private val number: String,
    /** The number this call presents, chosen in the dialer. Never inferred. */
    private val fromDid: String?,
    private val pathfinder: Pathfinder,
    /** True when the call arrived and this connection only has to report it. */
    private val incoming: Boolean = false,
) : Connection() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    fun begin() {
        setConnectionProperties(PROPERTY_SELF_MANAGED)
        setAddress(Uri.fromParts("tel", number, null), TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName("Voice", TelecomManager.PRESENTATION_ALLOWED)
        // VoIP mode, so Telecom owns the communication route and with it earpiece, speaker and
        // Bluetooth. Owning it ourselves works for the speaker and costs Bluetooth, which is
        // the wrong trade: this flag is the reason to involve Telecom at all.
        // Telecom owns the route again, on the strength of the endpoint API rather than the
        // legacy one. `setAudioRoute` was measured accepting the request and reporting the
        // speaker while the call stayed in the earpiece, so the app routed audio itself. The
        // endpoint call is the one Bluetooth rides on, and whether it moves a self-managed
        // call on this device is the question this setting now tests.
        setAudioModeIsVoip(true)
        TelecomBridge.telecomOwnsAudio = true
        TelecomBridge.connection = this
        if (incoming) {
            // The call already exists: the INVITE lives in Pathfinder and the person answers
            // from the app or the notification. This connection exists so the platform owns
            // audio routing and shows the call, which an outgoing one gets from placeCall.
            setRinging()
            pathfinder.noteFromElsewhere("telecom: incoming connection for $number")
        } else {
            setInitializing()
            pathfinder.dialOut(number, fromDid)
            pathfinder.noteFromElsewhere("telecom: connection created for $number")
        }
        watcher = scope.launch {
            // `callState` rather than `state`: registration lives on the other flow, so there
            // is nothing here to mistake for the end of a call.
            // `callState` is a StateFlow, so a new connection is handed the previous call's last
            // value the moment it subscribes. If that value is `Ended`, this connection finished
            // itself before its own call began and cleared itself out of the bridge, after which
            // every route request fell back to the audio manager and lost to Telecom.
            var live = false
            pathfinder.callState.collect { state ->
                if (state is PathState.Dialing || state is PathState.Ringing ||
                    state is PathState.Connected
                ) {
                    live = true
                }
                if (!live && (state is PathState.Ended || state is PathState.Failed)) return@collect
                when (state) {
                    null -> Unit
                    is PathState.Dialing -> setDialing()
                    is PathState.Ringing -> setRinging()
                    is PathState.Connected -> setActive()
                    is PathState.Ended -> finish(state.reason, DisconnectCause.LOCAL)
                    is PathState.Failed -> finish(state.reason, DisconnectCause.ERROR)
                    else -> Unit
                }
            }
        }
    }

    /**
     * The route this call should be on.
     *
     * Held rather than applied straight away: Telecom ignores a route request made before the
     * call is active, which is why asking at the moment of dialling looked accepted and changed
     * nothing. See [onStateChanged], where it is applied at the first moment it can be.
     */
    private var desiredSpeaker: Boolean? = null

    /** Moves the call between earpiece and speaker, which only Telecom can do here. */
    fun routeToSpeaker(on: Boolean) {
        desiredSpeaker = on
        if (state != STATE_ACTIVE) {
            pathfinder.noteFromElsewhere("telecom: will route to ${if (on) "speaker" else "earpiece"} when active")
            return
        }
        applyRoute()
    }

    override fun onStateChanged(state: Int) {
        // The first ACTIVE is where a route request becomes meaningful.
        if (state == STATE_ACTIVE && desiredSpeaker != null) applyRoute()
    }

    /**
     * Endpoints the platform says this call can be on, learned from Telecom itself.
     *
     * This is the modern routing model and the one Bluetooth rides on. The legacy
     * `setAudioRoute` was measured here accepting the request, reporting the speaker, and
     * leaving the audio in the earpiece, so the endpoint call is tried first and the legacy one
     * is kept only for a call that has no endpoints to offer.
     */
    private var endpoints: List<CallEndpoint> = emptyList()

    override fun onAvailableCallEndpointsChanged(endpoints: List<CallEndpoint>) {
        this.endpoints = endpoints
        val names = endpoints.joinToString { "${it.endpointName}" }
        pathfinder.noteFromElsewhere("telecom: endpoints are $names")
    }

    override fun onCallEndpointChanged(endpoint: CallEndpoint) {
        // This is the endpoint world's report of the route, and it is the only one a call that
        // has endpoints still sends: `onCallAudioStateChanged` goes quiet once they exist. The
        // screen was still listening to the legacy callback, so it kept reading earpiece after
        // Telecom had moved the call to the speaker, and every press asked for the speaker
        // that was already in use.
        pathfinder.noteFromElsewhere("telecom: endpoint is now ${endpoint.endpointName}")
        pathfinder.noteRouteFromPlatform(endpoint.endpointType == CallEndpoint.TYPE_SPEAKER)
    }

    private fun applyRoute() {
        val on = desiredSpeaker ?: return
        val wanted = if (on) CallEndpoint.TYPE_SPEAKER else CallEndpoint.TYPE_EARPIECE
        val endpoint = endpoints.firstOrNull { it.endpointType == wanted }
        if (endpoint == null) {
            // Deliberately not falling back to `setAudioRoute`. That call accepts the request and
            // reports the route it was asked for whether or not audio moves, which is how an
            // evening went by believing the speaker worked. A missing endpoint is the answer,
            // not something to route around.
            pathfinder.noteFromElsewhere(
                "telecom: no ${if (on) "speaker" else "earpiece"} endpoint offered, " +
                    "so no route was asked for",
            )
            return
        }
        pathfinder.noteFromElsewhere("telecom: asking for the ${endpoint.endpointName} endpoint")
        requestCallEndpointChange(
            endpoint,
            // A Connection is not a Context, and this callback only writes a log line.
            java.util.concurrent.Executor { command -> command.run() },
            object : OutcomeReceiver<Void?, CallEndpointException> {
                override fun onResult(result: Void?) {
                    pathfinder.noteFromElsewhere("telecom: endpoint ${endpoint.endpointName} accepted")
                }

                override fun onError(error: CallEndpointException) {
                    pathfinder.noteFromElsewhere(
                        "telecom: endpoint ${endpoint.endpointName} refused, " +
                            (error.message ?: error.javaClass.simpleName),
                    )
                }
            },
        )
    }

    /**
     * What the platform actually did with the route.
     *
     * Worth logging: the app can ask for the speaker and be told nothing, and this is the only
     * place that says whether the request landed or was overruled by a headset or the system.
     */
    override fun onCallAudioStateChanged(state: CallAudioState) {
        val route = when (state.route) {
            CallAudioState.ROUTE_SPEAKER -> "speaker"
            CallAudioState.ROUTE_EARPIECE -> "earpiece"
            CallAudioState.ROUTE_BLUETOOTH -> "bluetooth"
            CallAudioState.ROUTE_WIRED_HEADSET -> "wired headset"
            else -> "route ${state.route}"
        }
        pathfinder.noteFromElsewhere("telecom: audio is on the $route")
        // The platform is the authority on which device is live. Without this the screen's
        // chip kept whatever it last asked for.
        pathfinder.noteRouteFromPlatform(state.route == CallAudioState.ROUTE_SPEAKER)
    }

    private fun finish(reason: String, code: Int) {
        if (TelecomBridge.connection === this) TelecomBridge.connection = null
        pathfinder.noteFromElsewhere("telecom: disconnecting ($reason)")
        setDisconnected(DisconnectCause(code, reason))
        watcher?.cancel()
        destroy()
    }

    /** Answered from the platform, which includes a headset button or a car kit. */
    override fun onAnswer() {
        pathfinder.answerIncoming()
    }

    /** Rejected from the platform. */
    override fun onReject() {
        pathfinder.declineIncoming()
        finish("declined", DisconnectCause.REJECTED)
    }

    /** The person hung up through the system UI, or a headset button did it for them. */
    override fun onDisconnect() {
        pathfinder.hangUp()
        finish("hung up", DisconnectCause.LOCAL)
    }

    override fun onAbort() {
        onDisconnect()
    }
}

/**
 * The service Telecom binds to.
 *
 * Only outgoing calls for now. An incoming call would arrive here as
 * [onCreateIncomingConnection], and answering is the phase after this one, so it is refused
 * with a cause the system can show rather than silently dropped.
 */
class VoiceConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        val app = application as PathfinderApp
        val number = request.address?.schemeSpecificPart.orEmpty()
        if (number.isBlank()) {
            return Connection.createFailedConnection(
                DisconnectCause(DisconnectCause.ERROR, "no number in the request"),
            )
        }
        return VoiceConnection(
            number = number,
            fromDid = request.extras?.getString(TelecomBridge.EXTRA_FROM_DID)
                ?: TelecomBridge.takePendingFromDid(),
            pathfinder = app.pathfinder,
        ).also { it.begin() }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        val app = application as PathfinderApp
        val caller = request.extras?.getString(TelecomBridge.EXTRA_CALLER).orEmpty()
        val did = request.extras?.getString(TelecomBridge.EXTRA_FROM_DID).orEmpty()
        return VoiceConnection(
            number = caller,
            fromDid = did,
            pathfinder = app.pathfinder,
            incoming = true,
        ).also { it.begin() }
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ) {
        // Nothing to do: the platform already told the caller.
    }
}
