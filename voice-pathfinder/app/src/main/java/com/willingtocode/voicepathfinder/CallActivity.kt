package com.willingtocode.voicepathfinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
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
        val pathfinder = (application as PathfinderApp).pathfinder
        lifecycleScope.launch {
            combine(pathfinder.incoming, pathfinder.callState) { ringing, call ->
                ringing != null || call is PathState.Connected || call is PathState.Dialing ||
                    call is PathState.Ringing
            }.collect { live -> if (!live) finish() }
        }
        setContent {
            PathfinderTheme { ProductRoot(pathfinder) }
        }
    }
}
