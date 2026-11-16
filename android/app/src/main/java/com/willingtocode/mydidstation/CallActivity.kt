package com.willingtocode.mydidstation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The call, shown over the keyguard.
 *
 * It exists because `turnScreenOn` only lights the display when an activity is brought up with
 * it, not when it is already visible and the screen has gone dark. Measured on 2026-11-10:
 * setting it from the running activity left `mWakefulness=Asleep` and the phone silent and dark
 * while it rang. A fresh launch by the notification's full-screen intent does turn the screen on.
 *
 * It hosts the same screens as [MainActivity]; what it adds is when they may be seen, not what
 * they are. It closes itself when no call is live, so a finished call cannot leave it behind.
 */
class CallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the content: the platform reads these when the window is shown.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val voice = (application as App).container.voice
        lifecycleScope.launch {
            // A single sample with neither a ring nor a call is not the end of a call. The ring
            // and the call are separate flows and answering writes both; `combine` feeds them
            // through a channel, so it can hand over the ring's clear with the previous, idle
            // call value and produce one sample where both read as empty. Closing on that sample
            // shut the window 16 ms after an answer on the handset, mid-call. So a gap waits, and
            // then asks the flows themselves rather than trusting the sample: the live value has
            // landed by then if there is one, and a real end still closes the window.
            combine(voice.incoming, voice.callState) { ringing, call -> liveCall(ringing, call) }
                .collectLatest { live ->
                    if (live) return@collectLatest
                    Log.i(
                        Voice.LOGCAT_TAG,
                        "call window: nothing live (ringing=${voice.incoming.value != null}, " +
                            "call=${voice.callState.value})",
                    )
                    delay(CLOSE_GRACE_MILLIS)
                    // Read the flows again: the sample that started this wait may have been the
                    // one that missed a write, so the decision is made on what is true now.
                    val ringing = voice.incoming.value
                    val call = voice.callState.value
                    Log.i(
                        Voice.LOGCAT_TAG,
                        "call window: after the wait (ringing=${ringing != null}, call=$call)",
                    )
                    if (!liveCall(ringing, call)) finish()
                }
        }
        // Answering from the notification brings this window up with the request attached, so
        // the answer and the screen are one action. The collector above is already watching and
        // has seen the ring, so it cannot close the window before the answer lands.
        answerIfAsked()
        setContent {
            // The same root as [MainActivity], because a call is a screen of this app rather
            // than a second app's shell. What this activity adds is when the window may be
            // seen, not what is in it.
            MyDidStationTheme {
                Root((application as App).container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // `singleTask`, so a second Answer tap lands here rather than in onCreate.
        setIntent(intent)
        answerIfAsked()
    }

    /**
     * Answers if this window was opened by the notification's Answer action.
     *
     * The extra is consumed, because an activity recreated for a configuration change would
     * otherwise answer whatever happens to be ringing at that moment.
     */
    private fun answerIfAsked() {
        if (!intent.getBooleanExtra(EXTRA_ANSWER, false)) return
        intent.removeExtra(EXTRA_ANSWER)
        (application as App).container.voice.answerIncoming()
    }

    companion object {
        /** Set to answer the ringing call as this window opens. */
        const val EXTRA_ANSWER = "com.willingtocode.mydidstation.voice.ANSWER_ON_OPEN"

        /**
         * How long a window with nothing live waits before it closes.
         *
         * Long enough for the two state writes of an answer to land, short enough that the end
         * of a call does not leave a dead screen sitting there.
         */
        private const val CLOSE_GRACE_MILLIS = 800L

        /**
         * The intent that brings this window up, optionally answering on the way.
         *
         * Shared so the notification's Answer action and [open] cannot drift apart: answering
         * from either one has to arrive at the same screen with the same extras.
         */
        fun launchIntent(context: Context, answer: Boolean = false): Intent =
            Intent(context, CallActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                .apply { if (answer) putExtra(EXTRA_ANSWER, true) }

        /**
         * Brings the call window up.
         *
         * Used where a call was answered outside this app, which means Android's own call
         * screen. That window belongs to the platform, so without this the call would run its
         * whole length with this app in the background. A notification's PendingIntent may
         * always start an activity; a direct start from here depends on the platform allowing
         * it, so a refusal is logged rather than taken as a failure of the call.
         */
        fun open(context: Context) {
            try {
                context.startActivity(launchIntent(context))
            } catch (refused: Exception) {
                Log.w(Voice.LOGCAT_TAG, "could not bring the call window up", refused)
            }
        }
    }
}

/**
 * A call is worth a window while it is ringing, dialling, or up.
 *
 * One rule for both the sample the window watches and the re-read it makes before closing, so
 * the two cannot disagree about what counts as a live call.
 */
internal fun liveCall(ringing: Voice.Incoming?, call: PathState?): Boolean =
    ringing != null || call is PathState.Connected || call is PathState.Dialing ||
        call is PathState.Ringing
