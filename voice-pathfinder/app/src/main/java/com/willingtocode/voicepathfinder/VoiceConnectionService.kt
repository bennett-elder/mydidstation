package com.willingtocode.voicepathfinder

import android.net.Uri
import android.telecom.CallAudioState
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
) : Connection() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    fun begin() {
        setConnectionProperties(PROPERTY_SELF_MANAGED)
        setAddress(Uri.fromParts("tel", number, null), TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName("Voice", TelecomManager.PRESENTATION_ALLOWED)
        // VoIP audio, so the platform routes it to the earpiece or whichever device the person
        // has chosen, and treats it as a call for focus purposes.
        setAudioModeIsVoip(true)
        setInitializing()
        TelecomBridge.connection = this
        pathfinder.dialOut(number, fromDid)
        pathfinder.noteFromElsewhere("telecom: connection created for $number")
        watcher = scope.launch {
            // `callState` rather than `state`: registration lives on the other flow, so there
            // is nothing here to mistake for the end of a call.
            pathfinder.callState.collect { state ->
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

    /** Moves the call between earpiece and speaker, which only Telecom can do here. */
    fun routeToSpeaker(on: Boolean) {
        setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
        pathfinder.noteFromElsewhere("telecom: route ${if (on) "speaker" else "earpiece"}")
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
    }

    private fun finish(reason: String, code: Int) {
        if (TelecomBridge.connection === this) TelecomBridge.connection = null
        pathfinder.noteFromElsewhere("telecom: disconnecting ($reason)")
        setDisconnected(DisconnectCause(code, reason))
        watcher?.cancel()
        destroy()
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
    ): Connection = Connection.createFailedConnection(
        DisconnectCause(DisconnectCause.REJECTED, "incoming calls are not built yet"),
    )

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ) {
        // Nothing to do: the platform already told the caller.
    }
}
