package com.willingtocode.voicepathfinder

import android.app.Application

/**
 * The process-wide holder.
 *
 * The controller outlives the activity, so a rotation does not drop a live call, and the
 * foreground service in [PathfinderService] reads the same instance rather than owning a
 * second copy of the engine.
 */
class PathfinderApp : Application() {

    val pathfinder: Pathfinder by lazy { Pathfinder(this) }

    override fun onCreate() {
        super.onCreate()
        // On every start, which is harmless: the platform replaces the record for the same
        // handle. Doing it here rather than on first use means a call can find a registered
        // account even when nobody has opened a screen yet.
        TelecomBridge.register(this)
    }
}
