package com.willingtocode.voicepathfinder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * The app's relationship with Android's call stack.
 *
 * A self-managed [PhoneAccount] is what makes this a call handler rather than an app that
 * happens to play audio: Telecom then owns audio focus, routing, Bluetooth, DND and the
 * lock-screen presence, and it ends a call for us when the system needs the line back.
 *
 * Self-managed, not managed, because these calls do not travel over the cellular radio. A
 * managed account would route them through the carrier, which is not our traffic.
 *
 * The account has to be enabled once, by the person using the phone, in Settings. There is no
 * way to grant it from inside the app: `enablePhoneAccount` is a system permission, and
 * asking politely is the whole mechanism.
 */
internal object TelecomBridge {

    private const val ACCOUNT_ID = "voice-pathfinder"

    /** The number the call should be presented from, chosen in the dialer. */
    const val EXTRA_FROM_DID = "com.willingtocode.voicepathfinder.FROM_DID"

    /** The caller, for an arriving call. */
    const val EXTRA_CALLER = "com.willingtocode.voicepathfinder.CALLER"

    /**
     * The choice made in the dialer, for the connection that is about to be created.
     *
     * Kept here as well as in the extras because the extras did not arrive: the connection was
     * created with nothing, so the dial refused rather than choosing a line. Same process, one
     * call at a time, so a field is enough.
     */
    @Volatile
    private var pendingFromDid: String? = null

    /** The choice, once. Cleared so a later call cannot inherit it. */
    fun takePendingFromDid(): String? {
        val chosen = pendingFromDid
        pendingFromDid = null
        return chosen
    }

    fun handle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(ComponentName(context, VoiceConnectionService::class.java), ACCOUNT_ID)

    private fun telecom(context: Context): TelecomManager? =
        context.getSystemService(TelecomManager::class.java)

    /**
     * The live connection, if a call is up.
     *
     * Audio routing during a self-managed call belongs to Telecom, not to AudioManager: the
     * platform sets the route on the connection and quietly overrides anything the app does
     * to `isSpeakerphoneOn`. This is how the speaker toggle reaches the thing that decides.
     */
    @Volatile
    var connection: VoiceConnection? = null

    /** Routes the live call, returning false when there is no call to route. */
    /** True while a Telecom connection owns this app's audio routing. */
    fun isCarrying(): Boolean = connection != null

    /**
     * True when Telecom holds the audio route, false when this app does.
     *
     * Set by the connection itself, because that is where the choice is made. The two things
     * are separate: a connection can exist purely to tell the platform a call is up, which is
     * what happens when [pathfinderOwnsAudio] is set.
     */
    @Volatile
    var telecomOwnsAudio: Boolean = false

    fun routeToSpeaker(on: Boolean): Boolean {
        val live = connection ?: return false
        live.routeToSpeaker(on)
        return true
    }

    /**
     * Registers the account, unless it is already there and already what we want.
     *
     * Not unconditional, because registering replaces the platform's record. Whether a
     * replacement keeps the person's enablement is not something this code can check: reading
     * the account's state needs READ_PHONE_NUMBERS, which this app has no other use for. So it
     * avoids the question by not re-registering an account that is already correct.
     */
    fun register(context: Context) {
        val existing = runCatching { telecom(context)?.getPhoneAccount(handle(context)) }.getOrNull()
        if (existing != null && existing.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)) return
        val account = PhoneAccount.builder(handle(context), "Voice")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setShortDescription("Calls over SIP")
            .build()
        runCatching { telecom(context)?.registerPhoneAccount(account) }
    }

    /**
     * Asks Telecom to place the call, returning null on success or the reason it refused.
     *
     * The attempt is the test, deliberately. Reading whether the account is enabled needs
     * `READ_PHONE_NUMBERS`, a sensitive permission this app has no other use for, and the
     * check threw a SecurityException that a `runCatching` turned into a quiet "no" — every
     * call took the direct path with nothing in the log to say why. Asking Telecom to place
     * the call fails in exactly the case the check was meant to detect, and it costs nothing
     * when it succeeds.
     */
    fun placeCall(context: Context, number: String, fromDid: String?): String? = try {
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context))
            // Carried through so the connection dials on the line that was chosen. Without it
            // the service would have to pick one, which is the fallback this app does not do.
            putString(EXTRA_FROM_DID, fromDid)
            pendingFromDid = fromDid
        }
        telecom(context)?.placeCall(Uri.fromParts("tel", number, null), extras)
        null
    } catch (fault: Exception) {
        fault.message?.takeIf { it.isNotBlank() } ?: fault.javaClass.simpleName
    }

    /**
     * Tells the platform a call is arriving.
     *
     * Without this an incoming call is ours alone: no connection, so no platform routing, and
     * the speaker toggle has nothing that outranks it. It is the inbound half of what
     * [placeCall] does for outbound.
     */
    fun reportIncoming(context: Context, caller: String, did: String): String? = try {
        val extras = Bundle().apply {
            putString(EXTRA_CALLER, caller)
            putString(EXTRA_FROM_DID, did)
        }
        telecom(context)?.addNewIncomingCall(handle(context), extras)
        null
    } catch (fault: Exception) {
        fault.message?.takeIf { it.isNotBlank() } ?: fault.javaClass.simpleName
    }

    /** Where the account gets enabled. The only place the platform allows it. */
    fun settingsIntent(): Intent =
        Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
