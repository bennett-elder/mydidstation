package com.willingtocode.voicepathfinder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether Android will let this app keep a line open while the phone sits idle.
 *
 * A registered line is kept alive by a foreground service and a 25 second keepalive. Doze
 * suspends both, so the binding lapses and calls go to voicemail while the screen says
 * everything is fine. The only honest answer is to ask the person for the exemption and then
 * report which state the device is actually in.
 */
internal object Battery {

    /** True when the phone will not idle this app's network and timers. */
    fun unrestricted(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * Shows the system dialog that asks for it.
     *
     * Deliberately the dialog rather than a silent change: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
     * exists for apps whose job is a background call, but the person granting it is a decision,
     * not a detail.
     */
    /**
     * Opens the system list of unrestricted apps, where the exemption can be taken away.
     *
     * There is no API to revoke it: the whitelist belongs to DeviceIdleController and only the
     * person, or adb, can remove an entry. So the most this app can do is stop making somebody
     * hunt through app info to find the switch.
     */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun ask(context: Context) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
