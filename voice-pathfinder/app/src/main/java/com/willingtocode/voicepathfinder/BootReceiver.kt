package com.willingtocode.voicepathfinder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Puts the line back after a restart.
 *
 * Registration lives in a foreground service, and a reboot clears it. Without this the phone
 * is unreachable until somebody opens the app and taps something, which is the one behaviour a
 * phone must not have: the whole point is that it answers when nobody is looking at it.
 *
 * The service itself decides whether there is anything to restore, so a phone with no numbers
 * chosen starts nothing and says so.
 */
internal class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? PathfinderApp ?: return
        // A restart is not "starting" in the sense the screens mean; the line list is read
        // from settings by the service, and what it publishes afterwards is the truth.
        app.pathfinder.noteFromElsewhere("the phone restarted, so the line is being brought back")
        ContextCompat.startForegroundService(
            context,
            Intent(context, PathfinderService::class.java),
        )
    }
}
