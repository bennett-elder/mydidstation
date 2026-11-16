package com.willingtocode.mydidstation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Puts the line back after a restart or an update.
 *
 * Registration lives in a foreground service, and both a reboot and a package update clear it.
 * A reboot clears it by definition; an update stops the process, and Android does not restart a
 * sticky service for an app it just replaced. Without this the phone is unreachable until
 * somebody opens the app and taps something, which is the one behaviour a phone must not have:
 * the whole point is that it answers when nobody is looking at it. Measured on the handset,
 * 2026-11-15: an install at 22:04:59 left only the WorkManager job running, and a call placed
 * afterwards never rang.
 *
 * The service itself decides whether there is anything to restore, so a phone with no numbers
 * chosen starts nothing and says so.
 */
internal class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val replaced = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && !replaced) return
        val app = context.applicationContext as? App ?: return
        // A restart or an update is not "starting" in the sense the screens mean; the line list
        // is read from settings by the service, and what it publishes afterwards is the truth.
        val why = if (replaced) "the app was updated" else "the phone restarted"
        app.container.voice.noteFromElsewhere("$why, so the line is being brought back")
        ContextCompat.startForegroundService(
            context,
            Intent(context, VoiceService::class.java),
        )
    }
}
